package com.example.safetypose

import java.util.ArrayDeque

class FpsTracker(
    private val windowSize: Int = 30
) {
    private val frameTimes = ArrayDeque<Long>(windowSize)

    @Synchronized
    fun markFrame(nowMillis: Long = System.currentTimeMillis()): Float {
        frameTimes.addLast(nowMillis)
        while (frameTimes.size > windowSize) {
            frameTimes.removeFirst()
        }
        return getCurrentFpsLocked()
    }

    @Synchronized
    fun getCurrentFps(): Float = getCurrentFpsLocked()

    private fun getCurrentFpsLocked(): Float {
        if (frameTimes.size < 2) return 0f
        val elapsedMillis = frameTimes.last - frameTimes.first
        if (elapsedMillis <= 0L) return 0f
        return (frameTimes.size - 1) * 1000f / elapsedMillis
    }
}
