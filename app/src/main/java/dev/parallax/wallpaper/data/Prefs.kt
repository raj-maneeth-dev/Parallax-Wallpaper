package dev.parallax.wallpaper.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val FILE = "parallax_prefs"
    const val MOTION_ENABLED = "motion_enabled"
    const val INVERT = "invert"
    const val TILT_RANGE_DEG = "tilt_range_deg"
    const val SHIFT_PCT = "shift_pct"
    const val SMOOTH_MS = "smooth_ms"
    const val RECENTER_SEC = "recenter_sec"
    const val FPS = "fps"
    const val CALIBRATE_TOKEN = "calibrate_token"
    const val IMAGE_REVISION = "image_revision"
    const val PARAMS_REVISION = "params_revision"

    // V2
    const val PROFILE = "profile"
    const val SENS = "sens"
    const val H_SENS = "h_sens"
    const val V_SENS = "v_sens"
    const val PERSPECTIVE = "perspective"
    const val DEPTH_CONTRAST = "depth_contrast"
    const val JITTER_HZ = "jitter_hz"
    const val SENSOR_HZ = "sensor_hz"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Increments several counters in ONE commit so listeners always see a consistent state. */
    fun bump(context: Context, vararg keys: String) {
        val p = get(context)
        val e = p.edit()
        for (k in keys) e.putInt(k, p.getInt(k, 0) + 1)
        e.apply()
    }
}

data class ParallaxSettings(
    val motionEnabled: Boolean = true,
    val invert: Boolean = false,
    val tiltRangeDeg: Float = 20f,
    val shiftPct: Float = 4f,
    val smoothMs: Float = 150f,
    val recenterSec: Float = 10f,
    val fps: Int = 60,
    val sens: Float = 1f,
    val hSens: Float = 1f,
    val vSens: Float = 1f,
    val perspective: Float = 1f,
    val zFar: Float = 3f,
    val jitterHz: Float = 2.5f,
    val sensorHz: Int = 60,
    val calibrateToken: Int = 0,
    val imageRevision: Int = 0,
    val paramsRevision: Int = 0
) {
    companion object {
        fun load(p: SharedPreferences): ParallaxSettings {
            val d = ParallaxSettings()
            return ParallaxSettings(
                motionEnabled = p.getBoolean(Prefs.MOTION_ENABLED, d.motionEnabled),
                invert = p.getBoolean(Prefs.INVERT, d.invert),
                tiltRangeDeg = p.getFloat(Prefs.TILT_RANGE_DEG, d.tiltRangeDeg),
                shiftPct = p.getFloat(Prefs.SHIFT_PCT, d.shiftPct),
                smoothMs = p.getFloat(Prefs.SMOOTH_MS, d.smoothMs),
                recenterSec = p.getFloat(Prefs.RECENTER_SEC, d.recenterSec),
                fps = p.getFloat(Prefs.FPS, d.fps.toFloat()).toInt(),
                sens = p.getFloat(Prefs.SENS, d.sens),
                hSens = p.getFloat(Prefs.H_SENS, d.hSens),
                vSens = p.getFloat(Prefs.V_SENS, d.vSens),
                perspective = p.getFloat(Prefs.PERSPECTIVE, d.perspective),
                zFar = p.getFloat(Prefs.DEPTH_CONTRAST, d.zFar),
                jitterHz = p.getFloat(Prefs.JITTER_HZ, d.jitterHz),
                sensorHz = p.getFloat(Prefs.SENSOR_HZ, d.sensorHz.toFloat()).toInt(),
                calibrateToken = p.getInt(Prefs.CALIBRATE_TOKEN, 0),
                imageRevision = p.getInt(Prefs.IMAGE_REVISION, 0),
                paramsRevision = p.getInt(Prefs.PARAMS_REVISION, 0)
            )
        }
    }
}
