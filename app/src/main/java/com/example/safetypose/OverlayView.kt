package com.example.safetypose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var safetyState: SafetyState? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var mirrorHorizontally = false

    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 255, 255, 255)
        strokeWidth = 3.dp()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        strokeWidth = 4.dp()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 18.sp()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun updatePose(
        landmarks: List<NormalizedLandmark>,
        safetyState: SafetyState?,
        imageWidth: Int,
        imageHeight: Int,
        mirrorHorizontally: Boolean
    ) {
        this.landmarks = landmarks
        this.safetyState = safetyState
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.mirrorHorizontally = mirrorHorizontally
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isNotEmpty()) {
            drawSkeleton(canvas)
            drawLandmarks(canvas)
            drawSpineArc(canvas)
        }
        drawSafetyStatus(canvas)
    }

    private fun drawSkeleton(canvas: Canvas) {
        POSE_CONNECTIONS.forEach { (start, end) ->
            val startPoint = landmarks.getOrNull(start)
            val endPoint = landmarks.getOrNull(end)
            if (startPoint == null || endPoint == null) return@forEach
            if (!startPoint.isVisible() || !endPoint.isVisible()) return@forEach

            val p1 = mapLandmark(startPoint)
            val p2 = mapLandmark(endPoint)
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, skeletonPaint)
        }
    }

    private fun drawLandmarks(canvas: Canvas) {
        landmarks.forEach { landmark ->
            if (!landmark.isVisible()) return@forEach
            val point = mapLandmark(landmark)
            canvas.drawCircle(point.x, point.y, 5.dp(), landmarkPaint)
        }
    }

    private fun drawSpineArc(canvas: Canvas) {
        val state = safetyState ?: return
        if (landmarks.size <= RIGHT_HIP) return

        val leftHip = landmarks[LEFT_HIP]
        val rightHip = landmarks[RIGHT_HIP]
        if (!leftHip.isVisible() || !rightHip.isVisible()) return

        val hip = midpoint(leftHip, rightHip)
        val radius = 44.dp()
        val arcBounds = RectF(hip.x - radius, hip.y - radius, hip.x + radius, hip.y + radius)
        canvas.drawArc(arcBounds, -90f, state.spineAngle.coerceIn(0f, 120f), false, arcPaint)
    }

    private fun drawSafetyStatus(canvas: Canvas) {
        val state = safetyState ?: return
        when (state.level) {
            SafetyLevel.UNSAFE -> drawBanner(canvas, Color.argb(224, 244, 67, 54), "\u26A0 UNSAFE POSTURE")
            SafetyLevel.WARNING -> drawBanner(canvas, Color.argb(224, 255, 152, 0), "WARNING POSTURE")
            SafetyLevel.SAFE -> drawSafeBadge(canvas)
        }
    }

    private fun drawBanner(canvas: Canvas, color: Int, message: String) {
        val bannerHeight = 56.dp()
        bannerPaint.color = color
        canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, bannerPaint)
        val textBaseline = bannerHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(message, width / 2f, textBaseline, textPaint)
    }

    private fun drawSafeBadge(canvas: Canvas) {
        if (landmarks.isEmpty()) return
        val badgeText = "SAFE"
        val horizontalPadding = 14.dp()
        val badgeHeight = 36.dp()
        val textWidth = textPaint.measureText(badgeText)
        val right = width - 16.dp()
        val top = 16.dp()
        val rect = RectF(
            right - textWidth - horizontalPadding * 2f,
            top,
            right,
            top + badgeHeight
        )
        badgePaint.color = Color.argb(220, 76, 175, 80)
        canvas.drawRoundRect(rect, 18.dp(), 18.dp(), badgePaint)
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(badgeText, rect.centerX(), baseline, textPaint)
    }

    private fun mapLandmark(landmark: NormalizedLandmark): PointF {
        val normalizedX = if (mirrorHorizontally) 1f - landmark.x() else landmark.x()
        val normalizedY = landmark.y()

        if (imageWidth <= 0 || imageHeight <= 0) {
            return PointF(normalizedX * width, normalizedY * height)
        }

        val scale = max(width / imageWidth.toFloat(), height / imageHeight.toFloat())
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val dx = (width - scaledWidth) / 2f
        val dy = (height - scaledHeight) / 2f
        return PointF(dx + normalizedX * scaledWidth, dy + normalizedY * scaledHeight)
    }

    private fun midpoint(p1: NormalizedLandmark, p2: NormalizedLandmark): PointF {
        val first = mapLandmark(p1)
        val second = mapLandmark(p2)
        return PointF((first.x + second.x) / 2f, (first.y + second.y) / 2f)
    }

    private fun Float.dp(): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics)
    }

    private fun Int.dp(): Float = toFloat().dp()

    private fun Int.sp(): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, toFloat(), resources.displayMetrics)
    }

    private fun NormalizedLandmark.isVisible(): Boolean {
        return visibility().orElse(1f) >= MIN_VISIBILITY
    }

    private companion object {
        const val MIN_VISIBILITY = 0.5f
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24

        val POSE_CONNECTIONS = listOf(
            0 to 1,
            1 to 2,
            2 to 3,
            3 to 7,
            0 to 4,
            4 to 5,
            5 to 6,
            6 to 8,
            9 to 10,
            11 to 12,
            11 to 13,
            13 to 15,
            15 to 17,
            15 to 19,
            15 to 21,
            17 to 19,
            12 to 14,
            14 to 16,
            16 to 18,
            16 to 20,
            16 to 22,
            18 to 20,
            11 to 23,
            12 to 24,
            23 to 24,
            23 to 25,
            24 to 26,
            25 to 27,
            26 to 28,
            27 to 29,
            28 to 30,
            29 to 31,
            30 to 32,
            27 to 31,
            28 to 32
        )
    }
}
