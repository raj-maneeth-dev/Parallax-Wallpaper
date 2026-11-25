package dev.parallax.wallpaper.data

import android.content.Context

/**
 * Presets that write a complete, coherent set of motion settings. The user can still fine-tune each
 * value afterwards. The numbers are tuned by feel and are starting points, not measurements.
 *
 * IOS is an approximation of how the iPhone depth/parallax effect behaves (soft, slightly floaty
 * response, slow drift back to neutral, subject clearly in front of a calmer background). It is not
 * derived from any Apple specification.
 */
enum class MotionProfile(
    val key: String,
    val label: String,
    val tiltRangeDeg: Float,
    val shiftPct: Float,
    val smoothMs: Float,
    val recenterSec: Float,
    val zFar: Float,
    val perspective: Float,
    val jitterHz: Float,
    val sensorHz: Int,
    val fps: Int
) {
    NATURAL("natural", "Natural", 20f, 4.0f, 150f, 10f, 3.0f, 1.0f, 2.5f, 60, 60),
    CINEMATIC("cinematic", "Cinematic", 16f, 6.5f, 190f, 12f, 5.0f, 2.0f, 3.0f, 60, 60),
    SUBTLE("subtle", "Subtle", 26f, 2.0f, 220f, 8f, 2.5f, 0.5f, 1.5f, 30, 60),
    IOS("ios", "iOS-style", 22f, 5.0f, 210f, 14f, 3.5f, 1.2f, 2.0f, 60, 60);

    /** Writes this profile into the settings. Invert and the three sensitivities are reset too. */
    fun apply(context: Context) {
        Prefs.get(context).edit()
            .putString(Prefs.PROFILE, key)
            .putBoolean(Prefs.INVERT, false)
            .putFloat(Prefs.TILT_RANGE_DEG, tiltRangeDeg)
            .putFloat(Prefs.SHIFT_PCT, shiftPct)
            .putFloat(Prefs.SMOOTH_MS, smoothMs)
            .putFloat(Prefs.RECENTER_SEC, recenterSec)
            .putFloat(Prefs.DEPTH_CONTRAST, zFar)
            .putFloat(Prefs.PERSPECTIVE, perspective)
            .putFloat(Prefs.JITTER_HZ, jitterHz)
            .putFloat(Prefs.SENSOR_HZ, sensorHz.toFloat())
            .putFloat(Prefs.FPS, fps.toFloat())
            .putFloat(Prefs.SENS, 1f)
            .putFloat(Prefs.H_SENS, 1f)
            .putFloat(Prefs.V_SENS, 1f)
            .apply()
    }

    companion object {
        fun fromKey(key: String?): MotionProfile = values().firstOrNull { it.key == key } ?: NATURAL
    }
}
