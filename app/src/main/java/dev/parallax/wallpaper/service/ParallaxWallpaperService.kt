package dev.parallax.wallpaper.service

import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.parallax.wallpaper.data.Prefs
import dev.parallax.wallpaper.gl.WallpaperGlThread

class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    private inner class ParallaxEngine : Engine() {
        private var gl: WallpaperGlThread? = null
        private lateinit var prefs: SharedPreferences

        // Must stay a field: SharedPreferences only holds listeners weakly.
        private val prefListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> gl?.settingsChanged() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            prefs = Prefs.get(applicationContext)
            gl = WallpaperGlThread(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            gl?.surfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            gl?.surfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            gl?.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            gl?.setVisible(visible)
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            gl?.release()
            gl = null
            super.onDestroy()
        }
    }
}
