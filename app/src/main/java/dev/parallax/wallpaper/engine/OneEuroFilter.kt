package dev.parallax.wallpaper.engine

import kotlin.math.abs

/**
 * Adaptive low-pass filter ("One Euro"): strong smoothing while the signal moves slowly (removes
 * sensor jitter) and light smoothing while it moves fast (keeps the wallpaper responsive).
 */
class OneEuroFilter(var minCutoffHz: Float, private val beta: Float = 4f, private val dCutoffHz: Float = 1f) {
    private var hasPrev = false
    private var xPrev = 0f
    private var dxPrev = 0f

    fun reset() {
        hasPrev = false
    }

    fun filter(x: Float, dtIn: Float): Float {
        val dt = dtIn.coerceIn(0.001f, 0.1f)
        if (!hasPrev) {
            hasPrev = true
            xPrev = x
            dxPrev = 0f
            return x
        }
        val dx = (x - xPrev) / dt
        val edx = dxPrev + alpha(dCutoffHz, dt) * (dx - dxPrev)
        val cutoff = minCutoffHz + beta * abs(edx)
        val xf = xPrev + alpha(cutoff, dt) * (x - xPrev)
        xPrev = xf
        dxPrev = edx
        return xf
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }
}
