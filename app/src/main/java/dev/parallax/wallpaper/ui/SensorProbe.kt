package dev.parallax.wallpaper.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.roundToInt

/** Reports what this device actually offers, so nothing about the hardware is assumed. */
object SensorProbe {

    private val interesting = listOf(
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_GYROSCOPE to "Gyroscope",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "Game rotation vector",
        Sensor.TYPE_ROTATION_VECTOR to "Rotation vector",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer"
    )

    fun report(context: Context): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sb = StringBuilder()

        val game = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val chosen = when {
            game != null -> "game rotation vector (hardware fusion)"
            rot != null -> "rotation vector (hardware fusion)"
            gyro != null && acc != null -> "gyro + accelerometer (software fusion)"
            acc != null -> "accelerometer only (reduced quality)"
            else -> "none: no motion sensors"
        }
        sb.append("Motion source in use: ").append(chosen).append("\n\n")

        for ((type, label) in interesting) {
            val s = sm.getDefaultSensor(type)
            if (s == null) {
                sb.append("- ").append(label).append(": not available\n")
            } else {
                val rate = if (s.minDelay > 0) "${1_000_000 / s.minDelay} Hz max" else "on-change"
                sb.append("- ").append(label).append(": ").append(s.name)
                    .append(" (").append(rate).append(")\n")
            }
        }

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val rates = display.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted()
            sb.append("\nDisplay refresh rates: ").append(rates.joinToString(", ")).append(" Hz")
        }
        return sb.toString()
    }
}
