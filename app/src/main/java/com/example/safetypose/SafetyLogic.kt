package com.example.safetypose

import android.graphics.PointF
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object SafetyLogic {
    private const val LEFT_SHOULDER = 11
    private const val RIGHT_SHOULDER = 12
    private const val LEFT_HIP = 23
    private const val RIGHT_HIP = 24
    private const val LEFT_KNEE = 25
    private const val LEFT_ANKLE = 27
    private const val MIN_VISIBILITY = 0.5f

    private val requiredLandmarks = intArrayOf(
        LEFT_SHOULDER,
        RIGHT_SHOULDER,
        LEFT_HIP,
        RIGHT_HIP,
        LEFT_KNEE,
        LEFT_ANKLE
    )

    /*
     * Thresholds are intentionally conservative for a workplace-safety demo:
     * 35 degrees starts a warning because sustained trunk flexion above this
     * range is a common ergonomic risk signal; 50 degrees is treated as unsafe
     * because deep forward bending increases back-load risk. A knee angle above
     * 160 degrees while bending means the worker is lifting with a mostly
     * straight leg pattern. Shoulder tilt above 15 degrees catches visibly
     * asymmetric carrying without reacting to tiny pose-estimation jitter.
     */
    private const val SPINE_WARNING_DEGREES = 35f
    private const val SPINE_UNSAFE_DEGREES = 50f
    private const val KNEE_STRAIGHT_DEGREES = 160f
    private const val SHOULDER_ASYMMETRY_DEGREES = 15f

    fun angleBetween(a: PointF, b: PointF, c: PointF): Float {
        val bax = a.x - b.x
        val bay = a.y - b.y
        val bcx = c.x - b.x
        val bcy = c.y - b.y
        val magnitudeA = sqrt(bax * bax + bay * bay)
        val magnitudeC = sqrt(bcx * bcx + bcy * bcy)

        if (magnitudeA == 0f || magnitudeC == 0f) return 0f

        val cosine = ((bax * bcx + bay * bcy) / (magnitudeA * magnitudeC)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    fun midpoint(p1: NormalizedLandmark, p2: NormalizedLandmark): PointF {
        return PointF((p1.x() + p2.x()) / 2f, (p1.y() + p2.y()) / 2f)
    }

    fun spineAngle(landmarks: List<NormalizedLandmark>): Float {
        val shoulderMidpoint = midpoint(landmarks[LEFT_SHOULDER], landmarks[RIGHT_SHOULDER])
        val hipMidpoint = midpoint(landmarks[LEFT_HIP], landmarks[RIGHT_HIP])
        val verticalReference = PointF(hipMidpoint.x, hipMidpoint.y + 0.1f)
        return 180f - angleBetween(shoulderMidpoint, hipMidpoint, verticalReference)
    }

    fun kneeAngle(landmarks: List<NormalizedLandmark>): Float {
        val hip = PointF(landmarks[LEFT_HIP].x(), landmarks[LEFT_HIP].y())
        val knee = PointF(landmarks[LEFT_KNEE].x(), landmarks[LEFT_KNEE].y())
        val ankle = PointF(landmarks[LEFT_ANKLE].x(), landmarks[LEFT_ANKLE].y())
        return angleBetween(hip, knee, ankle)
    }

    fun isAsymmetricShoulder(landmarks: List<NormalizedLandmark>): Boolean {
        val left = landmarks[LEFT_SHOULDER]
        val right = landmarks[RIGHT_SHOULDER]
        val shoulderTiltDegrees = abs(
            Math.toDegrees(
                atan2(
                    (left.y() - right.y()).toDouble(),
                    (left.x() - right.x()).toDouble()
                )
            ).toFloat()
        )
        val normalizedTilt = min(shoulderTiltDegrees, 180f - shoulderTiltDegrees)
        return normalizedTilt > SHOULDER_ASYMMETRY_DEGREES
    }

    fun hasVisibleRequiredLandmarks(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size <= requiredLandmarks.max()) return false
        return requiredLandmarks.all { index -> landmarks[index].visibilityOrDefault() >= MIN_VISIBILITY }
    }

    fun evaluate(landmarks: List<NormalizedLandmark>): SafetyState {
        if (!hasVisibleRequiredLandmarks(landmarks)) {
            return SafetyState(
                spineAngle = 0f,
                kneeAngle = 0f,
                isAsymmetric = false,
                level = SafetyLevel.SAFE,
                reason = "Pose not detected clearly"
            )
        }

        val spineAngle = max(0f, spineAngle(landmarks))
        val kneeAngle = kneeAngle(landmarks)
        val asymmetric = isAsymmetricShoulder(landmarks)

        return when {
            spineAngle > SPINE_UNSAFE_DEGREES -> SafetyState(
                spineAngle = spineAngle,
                kneeAngle = kneeAngle,
                isAsymmetric = asymmetric,
                level = SafetyLevel.UNSAFE,
                reason = "Spine bent ${spineAngle.formatDegrees()} - straighten your back"
            )

            spineAngle > SPINE_WARNING_DEGREES && kneeAngle > KNEE_STRAIGHT_DEGREES -> SafetyState(
                spineAngle = spineAngle,
                kneeAngle = kneeAngle,
                isAsymmetric = asymmetric,
                level = SafetyLevel.UNSAFE,
                reason = "Back lifting detected - bend your knees"
            )

            spineAngle >= SPINE_WARNING_DEGREES -> SafetyState(
                spineAngle = spineAngle,
                kneeAngle = kneeAngle,
                isAsymmetric = asymmetric,
                level = SafetyLevel.WARNING,
                reason = "Spine bent ${spineAngle.formatDegrees()} - keep your back neutral"
            )

            asymmetric -> SafetyState(
                spineAngle = spineAngle,
                kneeAngle = kneeAngle,
                isAsymmetric = true,
                level = SafetyLevel.WARNING,
                reason = "Shoulders uneven - balance the load"
            )

            else -> SafetyState(
                spineAngle = spineAngle,
                kneeAngle = kneeAngle,
                isAsymmetric = false,
                level = SafetyLevel.SAFE,
                reason = "Posture looks safe"
            )
        }
    }

    class SafetyStateStabilizer(
        private val requiredFrames: Int = 8
    ) {
        private var displayedState: SafetyState? = null
        private var candidateLevel: SafetyLevel? = null
        private var candidateFrames = 0

        fun update(newState: SafetyState): SafetyState {
            val current = displayedState
            if (current == null || current.level == newState.level) {
                displayedState = newState
                candidateLevel = null
                candidateFrames = 0
                return newState
            }

            if (candidateLevel == newState.level) {
                candidateFrames += 1
            } else {
                candidateLevel = newState.level
                candidateFrames = 1
            }

            if (candidateFrames >= requiredFrames) {
                displayedState = newState
                candidateLevel = null
                candidateFrames = 0
            }

            return displayedState ?: newState
        }

        fun reset() {
            displayedState = null
            candidateLevel = null
            candidateFrames = 0
        }
    }
}

data class SafetyState(
    val spineAngle: Float,
    val kneeAngle: Float,
    val isAsymmetric: Boolean,
    val level: SafetyLevel,
    val reason: String
)

enum class SafetyLevel {
    SAFE,
    WARNING,
    UNSAFE
}

private fun NormalizedLandmark.visibilityOrDefault(): Float {
    return visibility().orElse(1f)
}

private fun Float.formatDegrees(): String {
    return String.format(Locale.US, "%.0f deg", this)
}
