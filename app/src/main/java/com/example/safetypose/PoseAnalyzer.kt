package com.example.safetypose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PoseFrameResult(
    val result: PoseLandmarkerResult,
    val imageWidth: Int,
    val imageHeight: Int,
    val fps: Float
)

class PoseAnalyzer(
    context: Context,
    private val onResult: (PoseFrameResult) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val isProcessing = AtomicBoolean(false)
    private val latestFps = AtomicReference(0f)
    private val fpsTracker = FpsTracker()

    private val poseLandmarker: PoseLandmarker = createPoseLandmarkerWithFallback(context)

    override fun analyze(imageProxy: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val timestampMillis = System.currentTimeMillis()
        latestFps.set(fpsTracker.markFrame(timestampMillis))

        try {
            val bitmap = imageProxy.toBitmap()
            val rotatedBitmap = bitmap.rotate(imageProxy.imageInfo.rotationDegrees)
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            poseLandmarker.detectAsync(mpImage, timestampMillis)
        } catch (error: RuntimeException) {
            isProcessing.set(false)
            onError(error.message ?: "Pose analysis failed")
        } finally {
            imageProxy.close()
        }
    }

    override fun close() {
        poseLandmarker.close()
    }

    private fun createPoseLandmarkerWithFallback(context: Context): PoseLandmarker {
        return try {
            createPoseLandmarker(context, Delegate.GPU)
        } catch (gpuError: RuntimeException) {
            createPoseLandmarker(context, Delegate.CPU)
        }
    }

    private fun createPoseLandmarker(context: Context, delegate: Delegate): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .setDelegate(delegate)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.6f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, input ->
                isProcessing.set(false)
                onResult(
                    PoseFrameResult(
                        result = result,
                        imageWidth = input.width,
                        imageHeight = input.height,
                        fps = latestFps.get()
                    )
                )
            }
            .setErrorListener { error ->
                isProcessing.set(false)
                onError(error.message ?: "Pose landmarker failed")
            }
            .build()

        return PoseLandmarker.createFromOptions(context, options)
    }

    private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return this
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private companion object {
        const val MODEL_ASSET_PATH = "pose_landmarker_lite.task"
    }
}
