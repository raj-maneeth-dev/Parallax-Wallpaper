package dev.parallax.wallpaper.gl

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import dev.parallax.wallpaper.data.ParallaxSettings
import dev.parallax.wallpaper.data.Prefs
import dev.parallax.wallpaper.data.ProjectStore
import dev.parallax.wallpaper.engine.MotionTracker
import dev.parallax.wallpaper.engine.Spring1D
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Owns the render thread for one surface (the wallpaper, or the editor preview): EGL, the layer
 * renderer, the sensors and one pair of springs per layer. Frames are scheduled on demand with
 * Choreographer: the loop runs only while visible AND something is still moving, then goes idle.
 * Sensor callbacks are delivered on this same thread, so no locking is needed.
 */
class WallpaperGlThread(private val context: Context) {

    private val thread = HandlerThread("parallax-gl").also { it.start() }
    private val handler = Handler(thread.looper)
    private val prefs = Prefs.get(context)

    private val egl = EglCore()
    private val renderer = LayerRenderer()
    private val springsX = ArrayList<Spring1D>()
    private val springsY = ArrayList<Spring1D>()
    private var posX = FloatArray(0)
    private var posY = FloatArray(0)
    private val motion = MotionTracker(context, handler) { requestFrame() }

    private var choreographer: Choreographer? = null
    private var surface: Surface? = null
    private var hasSurface = false
    private var width = 0
    private var height = 0
    private var visible = false
    private var frameScheduled = false
    private var lastFrameNs = 0L
    private var loadedRevision = -1
    private var loadedParamsRevision = -1
    private var handledCalibrateToken = prefs.getInt(Prefs.CALIBRATE_TOKEN, 0)
    private var settings = ParallaxSettings.load(prefs)

    private val frameCallback = Choreographer.FrameCallback { t -> onFrame(t) }

    init {
        handler.post { choreographer = Choreographer.getInstance() }
    }

    // ---- public API (any thread) ----

    fun surfaceCreated(s: Surface) {
        handler.post { doSurfaceCreated(s) }
    }

    fun surfaceChanged(w: Int, h: Int) {
        handler.post { doSurfaceChanged(w, h) }
    }

    fun surfaceDestroyed() {
        postAndWait { doSurfaceDestroyed() }
    }

    fun setVisible(v: Boolean) {
        handler.post { doSetVisible(v) }
    }

    fun settingsChanged() {
        handler.post { applySettings() }
    }

    fun release() {
        postAndWait {
            motion.stop()
            choreographer?.removeFrameCallback(frameCallback)
            doSurfaceDestroyed()
        }
        thread.quitSafely()
    }

    // ---- render thread ----

    private fun postAndWait(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun doSurfaceCreated(s: Surface) {
        try {
            egl.init()
            egl.createSurface(s)
            renderer.init()
            surface = s
            hasSurface = true
            loadedRevision = -1
            loadedParamsRevision = -1
            applyFrameRateHint()
        } catch (t: Throwable) {
            Log.e(TAG, "GL init failed", t)
            egl.release()
            hasSurface = false
            surface = null
        }
    }

    private fun doSurfaceChanged(w: Int, h: Int) {
        if (!hasSurface) return
        width = w
        height = h
        renderer.setViewport(w, h)

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rotation = dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        motion.setDisplayRotation(rotation)

        loadedRevision = -1 // re-decode for the new size
        applySettings()
    }

    private fun doSurfaceDestroyed() {
        choreographer?.removeFrameCallback(frameCallback)
        frameScheduled = false
        if (hasSurface) {
            renderer.release()
        }
        egl.release()
        hasSurface = false
        surface = null
        width = 0
        height = 0
    }

    private fun doSetVisible(v: Boolean) {
        visible = v
        if (v) {
            lastFrameNs = 0L
            updateSensorState()
            requestFrame()
        } else {
            motion.stop()
            choreographer?.removeFrameCallback(frameCallback)
            frameScheduled = false
        }
    }

    private fun applySettings() {
        val s = ParallaxSettings.load(prefs)
        settings = s

        val tiltRad = Math.toRadians(s.tiltRangeDeg.toDouble()).toFloat()
        motion.maxTiltXRad = tiltRad / (s.sens * s.hSens).coerceAtLeast(0.1f)
        motion.maxTiltYRad = tiltRad / (s.sens * s.vSens).coerceAtLeast(0.1f)
        motion.invert = s.invert
        motion.recenterSec = s.recenterSec
        motion.jitterHz = s.jitterHz
        if (s.calibrateToken != handledCalibrateToken) {
            handledCalibrateToken = s.calibrateToken
            motion.calibrate()
        }

        if (hasSurface && width > 0) {
            if (s.imageRevision != loadedRevision) {
                loadProject(s)
            } else if (s.paramsRevision != loadedParamsRevision) {
                reloadParams(s)
            }
        }
        applyFrameRateHint()
        updateSensorState()
        requestFrame()
    }

    private fun loadProject(s: ParallaxSettings) {
        try {
            renderer.clearLayers()
            val project = ProjectStore.load(context)
            renderer.setCanvas(project.canvasW, project.canvasH)
            var added = 0
            for (spec in project.layers) {
                if (!spec.enabled) continue
                val bmp = ImageSource.decodeLayer(context, spec, width, height, renderer.maxTextureSize)
                    ?: continue
                renderer.addLayer(spec, bmp)
                bmp.recycle()
                added++
            }
            if (added == 0) {
                val bmp = ImageSource.testScene(width, height)
                renderer.setCanvas(bmp.width, bmp.height)
                renderer.addLayer(ImageSource.TEST_SPEC, bmp)
                bmp.recycle()
            }
            rebuildSprings()
            loadedRevision = s.imageRevision
            loadedParamsRevision = s.paramsRevision
        } catch (t: Throwable) {
            Log.e(TAG, "Project load failed", t)
        }
    }

    private fun reloadParams(s: ParallaxSettings) {
        try {
            val project = ProjectStore.load(context)
            renderer.updateSpecs(project.layers)
        } catch (t: Throwable) {
            Log.e(TAG, "Param reload failed", t)
        }
        loadedParamsRevision = s.paramsRevision
    }

    private fun rebuildSprings() {
        val n = renderer.layerCount
        while (springsX.size < n) {
            springsX.add(Spring1D())
            springsY.add(Spring1D())
        }
        while (springsX.size > n) {
            springsX.removeAt(springsX.size - 1)
            springsY.removeAt(springsY.size - 1)
        }
        for (i in 0 until n) {
            springsX[i].reset(motion.targetX)
            springsY[i].reset(motion.targetY)
        }
        posX = FloatArray(n)
        posY = FloatArray(n)
    }

    private fun updateSensorState() {
        if (visible && hasSurface && settings.motionEnabled) {
            motion.start(1_000_000 / settings.sensorHz.coerceIn(10, 200))
        } else {
            motion.stop()
            motion.resetTarget()
        }
    }

    private fun applyFrameRateHint() {
        val s = surface ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                s.setFrameRate(settings.fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (t: Throwable) {
                Log.w(TAG, "setFrameRate failed", t)
            }
        }
    }

    private fun requestFrame() {
        if (!visible || !hasSurface || width == 0 || frameScheduled) return
        val c = choreographer ?: return
        frameScheduled = true
        c.postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!visible || !hasSurface || width == 0) return

        // Frame-rate cap (the display may run faster than the user's chosen cap).
        val minIntervalNs = 1_000_000_000L / settings.fps.coerceIn(10, 240)
        if (lastFrameNs != 0L && frameTimeNanos - lastFrameNs < minIntervalNs - 1_500_000L) {
            requestFrame()
            return
        }
        val dt = if (lastFrameNs == 0L) 1f / 60f
        else min((frameTimeNanos - lastFrameNs) * 1e-9f, 0.05f)
        lastFrameNs = frameTimeNanos

        val smooth = settings.smoothMs / 1000f
        val n = renderer.layerCount
        var settled = true
        for (i in 0 until n) {
            val st = smooth * renderer.specAt(i).smoothing
            springsX[i].update(motion.targetX, st, dt)
            springsY[i].update(motion.targetY, st, dt)
            posX[i] = springsX[i].position
            posY[i] = springsY[i].position
            if (!springsX[i].isSettled(motion.targetX) || !springsY[i].isSettled(motion.targetY)) {
                settled = false
            }
        }

        renderer.draw(width, height, settings, posX, posY)
        if (!egl.swap()) {
            Log.w(TAG, "eglSwapBuffers failed")
        }

        if (settled) {
            lastFrameNs = 0L // fully idle until the sensors move again
        } else {
            requestFrame()
        }
    }

    companion object {
        private const val TAG = "ParallaxGL"
    }
}
