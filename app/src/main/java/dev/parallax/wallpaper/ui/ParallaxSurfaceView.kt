package dev.parallax.wallpaper.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.parallax.wallpaper.data.Prefs
import dev.parallax.wallpaper.gl.WallpaperGlThread

/** In-app live preview: the same render thread, renderer and sensors as the real wallpaper. */
class ParallaxSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val gl = WallpaperGlThread(context.applicationContext)
    private val prefs = Prefs.get(context)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        gl.settingsChanged()
    }

    init {
        holder.addCallback(this)
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        gl.surfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        gl.surfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        gl.surfaceDestroyed()
    }

    fun onResume() {
        gl.setVisible(true)
    }

    fun onPause() {
        gl.setVisible(false)
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        gl.release()
    }
}
