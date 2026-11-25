package dev.parallax.wallpaper.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Turns sensor data into a normalised tilt target in (-1, 1) per screen axis.
 *
 * Pipeline: orientation (hardware rotation vector, or software gyro+accelerometer fusion) ->
 * screen normal in the neutral pose's frame -> two angles -> display-rotation remap -> slow
 * re-centering (bounds any gyro/yaw drift) -> One Euro low-pass (removes jitter) -> tanh soft limit.
 * Runs entirely on the supplied handler's thread.
 */
class MotionTracker(
    context: Context,
    private val handler: Handler,
    private val onTarget: () -> Unit
) : SensorEventListener {

    enum class Source { GAME_ROTATION_VECTOR, ROTATION_VECTOR, GYRO_ACCEL, ACCEL_ONLY, NONE }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotSensor: Sensor?
    val source: Source

    init {
        val game = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        when {
            game != null -> { rotSensor = game; source = Source.GAME_ROTATION_VECTOR }
            rotation != null -> { rotSensor = rotation; source = Source.ROTATION_VECTOR }
            gyro != null && accel != null -> { rotSensor = null; source = Source.GYRO_ACCEL }
            accel != null -> { rotSensor = null; source = Source.ACCEL_ONLY }
            else -> { rotSensor = null; source = Source.NONE }
        }
    }

    // Configuration (set from the handler thread)
    var maxTiltXRad = Math.toRadians(20.0).toFloat()
    var maxTiltYRad = Math.toRadians(20.0).toFloat()
    var invert = false
    var recenterSec = 10f
    var jitterHz = 2.5f
        set(value) {
            field = value
            filterX.minCutoffHz = value
            filterY.minCutoffHz = value
        }

    // Output: normalised target for the smoothers, screen axes, +x right, +y up
    var targetX = 0f
        private set
    var targetY = 0f
        private set

    private val filterX = OneEuroFilter(2.5f)
    private val filterY = OneEuroFilter(2.5f)
    private var lastNotifiedX = 0f
    private var lastNotifiedY = 0f

    private var started = false
    private var periodUs = 0
    private var displayRotation = Surface.ROTATION_0
    private val rot = FloatArray(9)
    private val ref = FloatArray(9)
    private var needRef = true
    private var neutralX = 0f
    private var neutralY = 0f
    private var lastNs = 0L
    private val filter = ComplementaryFilter()

    // accel-only reference
    private var refSide = 0f
    private var refPitch = 0f

    /**
     * Starts (or re-rates) sensor delivery. Calling it again with the same period is a no-op;
     * a different period re-registers the listeners WITHOUT resetting the neutral pose.
     */
    fun start(samplingPeriodUs: Int, recalibrate: Boolean = true) {
        if (source == Source.NONE) return
        var recal = recalibrate
        if (started) {
            if (samplingPeriodUs == periodUs) return
            sm.unregisterListener(this)
            started = false
            recal = false
        }
        started = true
        periodUs = samplingPeriodUs
        if (recal) {
            calibrate()
            filter.reset()
        }
        when (source) {
            Source.GAME_ROTATION_VECTOR, Source.ROTATION_VECTOR ->
                sm.registerListener(this, rotSensor, samplingPeriodUs, handler)
            Source.GYRO_ACCEL -> {
                sm.registerListener(this, accel, samplingPeriodUs, handler)
                sm.registerListener(this, gyro, samplingPeriodUs, handler)
            }
            Source.ACCEL_ONLY -> sm.registerListener(this, accel, samplingPeriodUs, handler)
            Source.NONE -> {}
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sm.unregisterListener(this)
    }

    /** Makes the current pose the neutral pose. */
    fun calibrate() {
        needRef = true
        neutralX = 0f
        neutralY = 0f
        lastNs = 0L
        filterX.reset()
        filterY.reset()
    }

    fun resetTarget() {
        targetX = 0f
        targetY = 0f
        lastNotifiedX = 0f
        lastNotifiedY = 0f
    }

    fun setDisplayRotation(rotation: Int) {
        if (rotation != displayRotation) {
            displayRotation = rotation
            calibrate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                processMatrix(rot, e.timestamp)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (source == Source.GYRO_ACCEL) {
                    filter.onAccel(e.values[0], e.values[1], e.values[2])
                } else if (source == Source.ACCEL_ONLY) {
                    processAccelOnly(e.values[0], e.values[1], e.values[2], e.timestamp)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (source == Source.GYRO_ACCEL &&
                    filter.onGyro(e.values[0], e.values[1], e.values[2], e.timestamp)
                ) {
                    filter.getMatrix(rot)
                    processMatrix(rot, e.timestamp)
                }
            }
        }
    }

    private fun processMatrix(r: FloatArray, ts: Long) {
        if (needRef) {
            System.arraycopy(r, 0, ref, 0, 9)
            needRef = false
            lastNs = 0L
        }
        // Screen normal of the current pose in the reference pose's device frame:
        // column 2 of (ref^T * r).
        val n0 = ref[0] * r[2] + ref[3] * r[5] + ref[6] * r[8]
        val n1 = ref[1] * r[2] + ref[4] * r[5] + ref[7] * r[8]
        val n2 = ref[2] * r[2] + ref[5] * r[5] + ref[8] * r[8]
        processAngles(atan2(n0, n2), atan2(n1, n2), ts)
    }

    private fun processAccelOnly(x: Float, y: Float, z: Float, ts: Long) {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1f) return
        val ux = x / n
        val uy = y / n
        val uz = z / n
        val side = -atan2(ux, sqrt(uy * uy + uz * uz))
        val pitch = atan2(uz, uy)
        if (needRef) {
            refSide = side
            refPitch = pitch
            needRef = false
            lastNs = 0L
        }
        var dp = pitch - refPitch
        val pi = Math.PI.toFloat()
        if (dp > pi) dp -= 2f * pi
        if (dp < -pi) dp += 2f * pi
        processAngles(side - refSide, dp, ts)
    }

    private fun processAngles(dx: Float, dy: Float, tsNs: Long) {
        val sx: Float
        val sy: Float
        when (displayRotation) {
            Surface.ROTATION_90 -> { sx = dy; sy = -dx }
            Surface.ROTATION_180 -> { sx = -dx; sy = -dy }
            Surface.ROTATION_270 -> { sx = -dy; sy = dx }
            else -> { sx = dx; sy = dy }
        }

        val dt = if (lastNs != 0L) ((tsNs - lastNs) * 1e-9f).coerceIn(0f, 0.2f) else 1f / 60f

        // Slow recentering: the neutral pose follows the device, so sustained postures and gyro
        // yaw drift fade out instead of accumulating.
        if (lastNs != 0L && recenterSec > 0f) {
            val k = 1f - exp(-dt / recenterSec)
            neutralX += (sx - neutralX) * k
            neutralY += (sy - neutralY) * k
        }
        lastNs = tsNs

        // Adaptive low-pass on the relative tilt, then soft saturation so edges can never show.
        val fx = filterX.filter(sx - neutralX, dt)
        val fy = filterY.filter(sy - neutralY, dt)
        val nx = tanh(fx / maxTiltXRad)
        val ny = tanh(fy / maxTiltYRad)

        // Content shifts opposite to the tilt (window-like). Invert flips this.
        val sign = if (invert) 1f else -1f
        targetX = nx * sign
        targetY = ny * sign

        if (abs(targetX - lastNotifiedX) > WAKE_EPS || abs(targetY - lastNotifiedY) > WAKE_EPS) {
            lastNotifiedX = targetX
            lastNotifiedY = targetY
            onTarget()
        }
    }

    companion object {
        private const val WAKE_EPS = 0.0015f
    }
}
