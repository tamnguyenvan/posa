package com.example.safetypose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.atomic.AtomicBoolean

data class YoloPoseFrameResult(
    val landmarks: List<NormalizedLandmark>,
    val imageWidth: Int,
    val imageHeight: Int,
    val fps: Float
)

class YoloPoseAnalyzer(
    context: Context,
    private val onResult: (YoloPoseFrameResult) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val isProcessing = AtomicBoolean(false)
    private val fpsTracker = FpsTracker()
    private val detector = YoloPoseDetector(context)

    override fun analyze(imageProxy: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
                .rotate(imageProxy.imageInfo.rotationDegrees)
                .toArgb8888()
            val landmarks = detector.detect(bitmap)
            onResult(
                YoloPoseFrameResult(
                    landmarks = landmarks,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    fps = fpsTracker.markFrame()
                )
            )
        } catch (error: Throwable) {
            onError(error.message ?: "YOLO26 pose analysis failed")
        } finally {
            isProcessing.set(false)
            imageProxy.close()
        }
    }

    override fun close() {
        detector.close()
    }

    private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return this
        val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.toArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this

        val converted = copy(Bitmap.Config.ARGB_8888, false)
        if (converted != null) return converted

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
            Canvas(target).drawBitmap(this, 0f, 0f, null)
        }
    }
}
