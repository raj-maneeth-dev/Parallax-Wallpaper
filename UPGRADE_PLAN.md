package dev.parallax.wallpaper.engine

import kotlin.math.sqrt

/**
 * Gyro + accelerometer fusion (Mahony-style proportional filter) used only when the device has no
 * hardware rotation-vector sensor. Produces a device->world rotation matrix, row-major, same
 * convention as SensorManager.getRotationMatrixFromVector.
 */
class ComplementaryFilter(private val gain: Float = 2.0f) {
    private var q0 = 1f
    private var q1 = 0f
    private var q2 = 0f
    private var q3 = 0f
    private var ax = 0f
    private var ay = 0f
    private var az = 1f
    private var hasAccel = false
    private var initialised = false
    private var lastGyroNs = 0L

    fun reset() {
        q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f
        hasAccel = false
        initialised = false
        lastGyroNs = 0L
    }

    fun onAccel(x: Float, y: Float, z: Float) {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 6f || n > 14f) return // ignore samples dominated by linear acceleration
        ax = x / n; ay = y / n; az = z / n
        hasAccel = true
        if (!initialised) {
            // Shortest-arc rotation taking the measured "up" vector onto world +Z.
            var w = 1f + az
            var qx = ay
            var qy = -ax
            if (w < 1e-3f) { w = 0f; qx = 1f; qy = 0f }
            q0 = w; q1 = qx; q2 = qy; q3 = 0f
            normalize()
            initialised = true
        }
    }

    /** Returns true when a valid orientation estimate is available after this sample. */
    fun onGyro(gxIn: Float, gyIn: Float, gzIn: Float, tsNs: Long): Boolean {
        val prev = lastGyroNs
        lastGyroNs = tsNs
        if (!initialised || prev == 0L) return false
        val dt = (tsNs - prev) * 1e-9f
        if (dt <= 0f || dt > 0.2f) return false

        var gx = gxIn
        var gy = gyIn
        var gz = gzIn
        if (hasAccel) {
            val hvx = q1 * q3 - q0 * q2
            val hvy = q0 * q1 + q2 * q3
            val hvz = q0 * q0 - 0.5f + q3 * q3
            val hex = ay * hvz - az * hvy
            val hey = az * hvx - ax * hvz
            val hez = ax * hvy - ay * hvx
            gx += gain * hex
            gy += gain * hey
            gz += gain * hez
        }
        val h = 0.5f * dt
        gx *= h; gy *= h; gz *= h
        val qa = q0
        val qb = q1
        val qc = q2
        q0 += -qb * gx - qc * gy - q3 * gz
        q1 += qa * gx + qc * gz - q3 * gy
        q2 += qa * gy - qb * gz + q3 * gx
        q3 += qa * gz + qb * gy - qc * gx
        normalize()
        return true
    }

    fun getMatrix(out: FloatArray) {
        val w = q0; val x = q1; val y = q2; val z = q3
        out[0] = 1f - 2f * (y * y + z * z)
        out[1] = 2f * (x * y - w * z)
        out[2] = 2f * (x * z + w * y)
        out[3] = 2f * (x * y + w * z)
        out[4] = 1f - 2f * (x * x + z * z)
        out[5] = 2f * (y * z - w * x)
        out[6] = 2f * (x * z - w * y)
        out[7] = 2f * (y * z + w * x)
        out[8] = 1f - 2f * (x * x + y * y)
    }

    private fun normalize() {
        val n = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (n < 1e-6f) { q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f; return }
        val inv = 1f / n
        q0 *= inv; q1 *= inv; q2 *= inv; q3 *= inv
    }
}
