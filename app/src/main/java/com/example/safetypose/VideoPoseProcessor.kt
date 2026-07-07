package com.example.safetypose

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

data class VideoFrameResult(
    val result: PoseLandmarkerResult,
    val bitmap: Bitmap,
    val imageWidth: Int,
    val imageHeight: Int,
    val progressPercent: Int,
    val processingFps: Float
)

object VideoPoseProcessor {
    fun process(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean,
        onFrame: (VideoFrameResult) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        var landmarker: PoseLandmarker? = null
        var sourceHandle: VideoSourceHandle? = null
        val retriever = MediaMetadataRetriever()

        try {
            landmarker = createPoseLandmarkerWithFallback(context)
            sourceHandle = retriever.setDataSourceFromUri(context, uri)

            val metadataDurationMs = retriever.extractLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val rotationDegrees = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val durationUs = if (metadataDurationMs > 0L) {
                metadataDurationMs * 1000L
            } else {
                DEFAULT_UNKNOWN_DURATION_US
            }
            val fpsTracker = FpsTracker()
            val playbackStartMs = SystemClock.elapsedRealtime()
            var timestampUs = 0L
            var consecutiveEmptyFrames = 0

            while (!isCancelled() && timestampUs <= durationUs) {
                val sourceBitmap = retriever.getFrameAtTime(
                    timestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (sourceBitmap != null) {
                    consecutiveEmptyFrames = 0
                    val bitmap = sourceBitmap
                        .toArgb8888()
                        .scaleDown(MAX_FRAME_EDGE)
                        .rotate(rotationDegrees)
                        .toArgb8888()
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    val result = landmarker.detectForVideo(mpImage, timestampUs / 1000L)
                    val progress = if (durationUs > 0L) {
                        ((timestampUs * 100L) / durationUs).coerceIn(0L, 100L).toInt()
                    } else {
                        100
                    }

                    onFrame(
                        VideoFrameResult(
                            result = result,
                            bitmap = bitmap,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            progressPercent = progress,
                            processingFps = fpsTracker.markFrame()
                        )
                    )
                    waitForPlaybackTime(playbackStartMs, timestampUs, isCancelled)
                } else {
                    consecutiveEmptyFrames += 1
                    if (metadataDurationMs <= 0L && consecutiveEmptyFrames >= MAX_EMPTY_FRAMES) {
                        break
                    }
                }

                timestampUs += FRAME_INTERVAL_US
            }

            if (!isCancelled()) {
                onComplete()
            }
        } catch (error: Exception) {
            if (!isCancelled()) {
                onError(error.videoErrorMessage())
            }
        } finally {
            landmarker?.close()
            retriever.release()
            sourceHandle?.close()
        }
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
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.6f)
            .setMinTrackingConfidence(0.5f)
            .build()

        return PoseLandmarker.createFromOptions(context, options)
    }

    private fun MediaMetadataRetriever.extractLong(keyCode: Int): Long {
        return extractMetadata(keyCode)?.toLongOrNull() ?: 0L
    }

    private fun MediaMetadataRetriever.extractInt(keyCode: Int): Int {
        return extractMetadata(keyCode)?.toIntOrNull() ?: 0
    }

    private fun MediaMetadataRetriever.setDataSourceFromUri(
        context: Context,
        uri: Uri
    ): VideoSourceHandle {
        val cacheFile = try {
            copyUriToCache(context, uri)
        } catch (error: IOException) {
            null
        }

        if (cacheFile != null) {
            try {
                setDataSource(cacheFile.absolutePath)
                return VideoSourceHandle(cacheFile = cacheFile)
            } catch (error: RuntimeException) {
                cacheFile.delete()
            }
        }

        val resolver = context.contentResolver
        val assetFileDescriptor = try {
            resolver.openAssetFileDescriptor(uri, "r")
        } catch (error: IOException) {
            null
        }

        if (assetFileDescriptor != null) {
            try {
                if (assetFileDescriptor.length >= 0L) {
                    setDataSource(
                        assetFileDescriptor.fileDescriptor,
                        assetFileDescriptor.startOffset,
                        assetFileDescriptor.length
                    )
                } else {
                    setDataSource(assetFileDescriptor.fileDescriptor)
                }
                return VideoSourceHandle(assetFileDescriptor = assetFileDescriptor)
            } catch (error: RuntimeException) {
                assetFileDescriptor.closeQuietly()
            }
        }

        throw IOException("Could not open selected video as a seekable source")
    }

    private fun copyUriToCache(context: Context, uri: Uri): File {
        val cacheFile = File.createTempFile("safety_pose_video_", ".tmp", context.cacheDir)
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open selected video stream")

        inputStream.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }

        return cacheFile
    }

    private fun Exception.videoErrorMessage(): String {
        val details = message?.takeIf { it.isNotBlank() }
        return if (details == null) {
            "Unable to read this video. Try a local MP4/H.264 video."
        } else {
            "Unable to read this video. Try a local MP4/H.264 video. Details: $details"
        }
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }

    private fun waitForPlaybackTime(
        playbackStartMs: Long,
        timestampUs: Long,
        isCancelled: () -> Boolean
    ) {
        val targetTimeMs = playbackStartMs + timestampUs / 1000L
        while (!isCancelled()) {
            val remainingMs = targetTimeMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return
            Thread.sleep(remainingMs.coerceAtMost(MAX_SLEEP_MS))
        }
    }

    private fun Bitmap.scaleDown(maxEdge: Int): Bitmap {
        val longestEdge = max(width, height)
        if (longestEdge <= maxEdge) return this

        val scale = maxEdge.toFloat() / longestEdge
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun Bitmap.toArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this

        val converted = copy(Bitmap.Config.ARGB_8888, false)
        if (converted != null) return converted

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
            Canvas(target).drawBitmap(this, 0f, 0f, null)
        }
    }

    private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (normalizedRotation == 0) return this

        val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private const val MODEL_ASSET_PATH = "pose_landmarker_lite.task"
    private const val MAX_FRAME_EDGE = 640
    private const val FRAME_INTERVAL_US = 200_000L
    private const val DEFAULT_UNKNOWN_DURATION_US = 60_000_000L
    private const val MAX_EMPTY_FRAMES = 8
    private const val MAX_SLEEP_MS = 50L

    private data class VideoSourceHandle(
        val assetFileDescriptor: AssetFileDescriptor? = null,
        val cacheFile: File? = null
    ) : Closeable {
        override fun close() {
            assetFileDescriptor?.closeQuietly()
            cacheFile?.delete()
        }
    }
}
