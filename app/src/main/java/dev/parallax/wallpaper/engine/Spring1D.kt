package dev.parallax.wallpaper.engine

import kotlin.math.abs
import kotlin.math.max

/** Critically damped spring (no overshoot). Frame-time aware, so smoothing feels identical at 60/90/120 Hz. */
class Spring1D {
    var position = 0f
        private set
    var velocity = 0f
        private set

    fun reset(value: Float = 0f) {
        position = value
        velocity = 0f
    }

    fun update(target: Float, smoothTimeSec: Float, dt: Float) {
        val st = max(0.0001f, smoothTimeSec)
        val omega = 2f / st
        val x = omega * dt
        val e = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        val change = position - target
        val temp = (velocity + omega * change) * dt
        velocity = (velocity - omega * temp) * e
        position = target + (change + temp) * e
    }

    fun isSettled(target: Float): Boolean =
        abs(position - target) < POS_EPS && abs(velocity) < VEL_EPS

    companion object {
        const val POS_EPS = 0.0004f
        const val VEL_EPS = 0.01f
    }
}
