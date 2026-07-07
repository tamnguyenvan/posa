package com.example.safetypose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Optional
import kotlin.math.min
import kotlin.math.roundToInt

class YoloPoseDetector(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val runtime = createRuntime(appContext)

    fun detect(source: Bitmap): List<NormalizedLandmark> {
        val preparedInput = prepareInput(source)
        val output = runtime.run(preparedInput.floatData)

        return decodeBestPose(
            data = output.data,
            shape = output.shape,
            sourceWidth = source.width,
            sourceHeight = source.height,
            letterbox = preparedInput.letterbox
        )
    }

    override fun close() {
        runtime.close()
    }

    private fun prepareInput(source: Bitmap): PreparedInput {
        val scale = min(INPUT_SIZE / source.width.toFloat(), INPUT_SIZE / source.height.toFloat())
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - targetWidth) / 2f
        val padY = (INPUT_SIZE - targetHeight) / 2f

        val inputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val scaledBitmap = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        Canvas(inputBitmap).apply {
            drawColor(Color.BLACK)
            drawBitmap(scaledBitmap, padX, padY, null)
        }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        inputBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val planeSize = INPUT_SIZE * INPUT_SIZE
        val floatData = FloatArray(CHANNEL_COUNT * planeSize)
        pixels.forEachIndexed { index, pixel ->
            floatData[index] = Color.red(pixel) / 255f
            floatData[planeSize + index] = Color.green(pixel) / 255f
            floatData[planeSize * 2 + index] = Color.blue(pixel) / 255f
        }

        if (scaledBitmap !== source) {
            scaledBitmap.recycle()
        }
        inputBitmap.recycle()

        return PreparedInput(
            floatData = floatData,
            letterbox = Letterbox(
                scale = scale,
                padX = padX,
                padY = padY
            )
        )
    }

    private fun decodeBestPose(
        data: FloatArray,
        shape: LongArray,
        sourceWidth: Int,
        sourceHeight: Int,
        letterbox: Letterbox
    ): List<NormalizedLandmark> {
        val output = YoloOutput.from(data, shape) ?: return emptyList()
        var bestIndex = -1
        var bestScore = MIN_PERSON_CONFIDENCE

        for (index in 0 until output.detectionCount) {
            val score = output.value(channel = 4, detection = index)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }

        if (bestIndex < 0) return emptyList()

        val landmarks = MutableList(MEDIAPIPE_LANDMARK_COUNT) { invisibleLandmark() }
        COCO_TO_MEDIAPIPE.forEachIndexed { cocoIndex, mediapipeIndex ->
            val baseChannel = output.keypointChannelOffset + cocoIndex * KEYPOINT_VALUES
            val modelX = output.value(channel = baseChannel, detection = bestIndex)
            val modelY = output.value(channel = baseChannel + 1, detection = bestIndex)
            val confidence = output.value(channel = baseChannel + 2, detection = bestIndex)
                .coerceIn(0f, 1f)

            val sourceX = ((modelX - letterbox.padX) / letterbox.scale)
                .coerceIn(0f, sourceWidth.toFloat())
            val sourceY = ((modelY - letterbox.padY) / letterbox.scale)
                .coerceIn(0f, sourceHeight.toFloat())
            val normalizedX = if (sourceWidth > 0) sourceX / sourceWidth else 0f
            val normalizedY = if (sourceHeight > 0) sourceY / sourceHeight else 0f

            landmarks[mediapipeIndex] = visibleLandmark(
                x = normalizedX,
                y = normalizedY,
                confidence = confidence
            )
        }

        copyLandmark(landmarks, from = 2, to = 1)
        copyLandmark(landmarks, from = 2, to = 3)
        copyLandmark(landmarks, from = 5, to = 4)
        copyLandmark(landmarks, from = 5, to = 6)

        return landmarks
    }

    private fun createRuntime(context: Context): YoloRuntime {
        return when {
            isExecuTorchAvailable(context) -> ExecuTorchRuntime(context)
            isLiteRtAvailable(context) -> LiteRtRuntime(context)
            else -> error(availabilityError(context) ?: "YOLO26 runtime is unavailable")
        }
    }

    private interface YoloRuntime : AutoCloseable {
        fun run(floatData: FloatArray): RawYoloOutput
    }

    private class ExecuTorchRuntime(context: Context) : YoloRuntime {
        private val module = Module.load(
            copyAssetToFile(context, EXECUTORCH_MODEL_ASSET_NAME).absolutePath
        )

        override fun run(floatData: FloatArray): RawYoloOutput {
            val tensor = Tensor.fromBlob(
                floatData,
                longArrayOf(1L, CHANNEL_COUNT.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            )
            val outputs = module.forward(EValue.from(tensor))
            val outputTensor = outputs.firstOrNull()?.toTensor() ?: error("YOLO26 output is empty")
            return RawYoloOutput(
                data = outputTensor.getDataAsFloatArray(),
                shape = outputTensor.shape()
            )
        }

        override fun close() {
            module.close()
        }
    }

    private class LiteRtRuntime(context: Context) : YoloRuntime {
        private val interpreter = Interpreter(
            copyAssetToFile(context, LITERT_MODEL_ASSET_NAME),
            Interpreter.Options()
                .setNumThreads(LITERT_THREAD_COUNT)
                .setUseXNNPACK(true)
        )
        private val inputShape = interpreter.getInputTensor(0).shape()
        private val inputUsesNhwc = inputShape.size == 4 && inputShape[3] == CHANNEL_COUNT

        init {
            interpreter.allocateTensors()
        }

        override fun run(floatData: FloatArray): RawYoloOutput {
            val inputData = if (inputUsesNhwc) floatData.toNhwc() else floatData
            val inputBuffer = ByteBuffer
                .allocateDirect(inputData.size * FLOAT_BYTES)
                .order(ByteOrder.nativeOrder())
            inputBuffer.asFloatBuffer().put(inputData)
            inputBuffer.rewind()

            val outputTensor = interpreter.getOutputTensor(0)
            val outputBuffer = ByteBuffer
                .allocateDirect(outputTensor.numBytes())
                .order(ByteOrder.nativeOrder())

            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val outputData = FloatArray(outputTensor.numBytes() / FLOAT_BYTES)
            outputBuffer.asFloatBuffer().get(outputData)
            return RawYoloOutput(
                data = outputData,
                shape = outputTensor.shape().map { dimension -> dimension.toLong() }.toLongArray()
            )
        }

        override fun close() {
            interpreter.close()
        }
    }

    private fun copyLandmark(
        landmarks: MutableList<NormalizedLandmark>,
        from: Int,
        to: Int
    ) {
        landmarks[to] = landmarks[from]
    }

    private data class PreparedInput(
        val floatData: FloatArray,
        val letterbox: Letterbox
    )

    private data class Letterbox(
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private data class RawYoloOutput(
        val data: FloatArray,
        val shape: LongArray
    )

    private class YoloOutput(
        private val data: FloatArray,
        private val channelCount: Int,
        val detectionCount: Int,
        private val channelMajor: Boolean
    ) {
        val keypointChannelOffset: Int =
            if (channelCount >= END_TO_END_OUTPUT_CHANNELS) END_TO_END_KEYPOINT_CHANNEL_OFFSET else RAW_KEYPOINT_CHANNEL_OFFSET

        fun value(channel: Int, detection: Int): Float {
            return if (channelMajor) {
                data[channel * detectionCount + detection]
            } else {
                data[detection * channelCount + channel]
            }
        }

        companion object {
            fun from(data: FloatArray, shape: LongArray): YoloOutput? {
                if (shape.size < 3) return null

                val first = shape[shape.size - 2].toInt()
                val second = shape[shape.size - 1].toInt()
                return when {
                    first == EXPECTED_OUTPUT_CHANNELS && data.size >= first * second -> {
                        YoloOutput(
                            data = data,
                            channelCount = first,
                            detectionCount = second,
                            channelMajor = true
                        )
                    }

                    second == EXPECTED_OUTPUT_CHANNELS && data.size >= first * second -> {
                        YoloOutput(
                            data = data,
                            channelCount = second,
                            detectionCount = first,
                            channelMajor = false
                        )
                    }

                    first in MIN_OUTPUT_CHANNELS..MAX_REASONABLE_CHANNELS && data.size >= first * second -> {
                        YoloOutput(
                            data = data,
                            channelCount = first,
                            detectionCount = second,
                            channelMajor = true
                        )
                    }

                    second in MIN_OUTPUT_CHANNELS..MAX_REASONABLE_CHANNELS && data.size >= first * second -> {
                        YoloOutput(
                            data = data,
                            channelCount = second,
                            detectionCount = first,
                            channelMajor = false
                        )
                    }

                    else -> null
                }
            }
        }
    }

    companion object {
        private const val EXECUTORCH_MODEL_ASSET_NAME = "yolo26n-pose.pte"
        private const val LITERT_MODEL_ASSET_NAME = "yolo26n-pose.tflite"
        private const val INPUT_SIZE = 640
        private const val CHANNEL_COUNT = 3
        private const val EXPECTED_OUTPUT_CHANNELS = 56
        private const val MIN_OUTPUT_CHANNELS = 56
        private const val MAX_REASONABLE_CHANNELS = 128
        private const val END_TO_END_OUTPUT_CHANNELS = 57
        private const val RAW_KEYPOINT_CHANNEL_OFFSET = 5
        private const val END_TO_END_KEYPOINT_CHANNEL_OFFSET = 6
        private const val KEYPOINT_VALUES = 3
        private const val MEDIAPIPE_LANDMARK_COUNT = 33
        private const val MIN_PERSON_CONFIDENCE = 0.25f
        private const val FLOAT_BYTES = 4
        private const val LITERT_THREAD_COUNT = 2

        private val EXECUTORCH_SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
        private val LITERT_SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

        private val COCO_TO_MEDIAPIPE = intArrayOf(
            0,
            2,
            5,
            7,
            8,
            11,
            12,
            13,
            14,
            15,
            16,
            23,
            24,
            25,
            26,
            27,
            28
        )

        fun availabilityError(context: Context): String? {
            if (isExecuTorchAvailable(context) || isLiteRtAvailable(context)) {
                return null
            }
            val supportsAnyRuntime = isExecuTorchRuntimeSupported() || isLiteRtRuntimeSupported()
            if (!supportsAnyRuntime) {
                val deviceAbis = Build.SUPPORTED_ABIS.joinToString()
                return context.getString(R.string.yolo26_unavailable, deviceAbis)
            }
            return context.getString(R.string.yolo26_model_missing)
        }

        private fun isExecuTorchAvailable(context: Context): Boolean {
            return isExecuTorchRuntimeSupported() && hasModelAsset(context, EXECUTORCH_MODEL_ASSET_NAME)
        }

        private fun isLiteRtAvailable(context: Context): Boolean {
            return isLiteRtRuntimeSupported() && hasModelAsset(context, LITERT_MODEL_ASSET_NAME)
        }

        private fun isExecuTorchRuntimeSupported(): Boolean {
            return Build.SUPPORTED_ABIS.any { abi -> abi in EXECUTORCH_SUPPORTED_ABIS }
        }

        private fun isLiteRtRuntimeSupported(): Boolean {
            return Build.SUPPORTED_ABIS.any { abi -> abi in LITERT_SUPPORTED_ABIS }
        }

        private fun hasModelAsset(context: Context, assetName: String): Boolean {
            return try {
                context.assets.open(assetName).use { true }
            } catch (_: Exception) {
                false
            }
        }

        private fun copyAssetToFile(context: Context, assetName: String): File {
            val modelDir = File(context.filesDir, "models").apply { mkdirs() }
            val target = File(modelDir, assetName)
            if (target.exists() && target.length() > 0L) return target

            context.assets.open(assetName).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            return target
        }

        private fun FloatArray.toNhwc(): FloatArray {
            val planeSize = INPUT_SIZE * INPUT_SIZE
            val output = FloatArray(size)
            for (index in 0 until planeSize) {
                val outputIndex = index * CHANNEL_COUNT
                output[outputIndex] = this[index]
                output[outputIndex + 1] = this[planeSize + index]
                output[outputIndex + 2] = this[planeSize * 2 + index]
            }
            return output
        }

        private fun invisibleLandmark(): NormalizedLandmark {
            return NormalizedLandmark.create(
                0f,
                0f,
                0f,
                Optional.of(0f),
                Optional.of(0f)
            )
        }

        private fun visibleLandmark(
            x: Float,
            y: Float,
            confidence: Float
        ): NormalizedLandmark {
            val visibility = confidence.coerceIn(0f, 1f)
            return NormalizedLandmark.create(
                x.coerceIn(0f, 1f),
                y.coerceIn(0f, 1f),
                0f,
                Optional.of(visibility),
                Optional.of(visibility)
            )
        }
    }
}
