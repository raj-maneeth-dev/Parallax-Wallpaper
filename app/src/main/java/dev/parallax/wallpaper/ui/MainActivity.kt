package dev.parallax.wallpaper.ui

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.parallax.wallpaper.data.MotionProfile
import dev.parallax.wallpaper.data.ParallaxSettings
import dev.parallax.wallpaper.data.Prefs
import dev.parallax.wallpaper.data.ProjectStore
import dev.parallax.wallpaper.data.WallpaperStore
import dev.parallax.wallpaper.service.ParallaxWallpaperService
import kotlin.math.roundToInt

/** Main console: apply the wallpaper, pick an image, choose a motion profile, tune, calibrate. */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "Parallax"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })

        addButton("Set as live wallpaper") { setWallpaper() }
        addButton("Choose image from gallery") { pickImage() }
        addButton("Layer editor (depth, live preview)") {
            startActivity(Intent(this, LayerEditorActivity::class.java))
        }
        addButton("Use built-in test scene") {
            WallpaperStore.clearImage(this)
            toast("Test scene restored")
        }
        addButton("Calibrate (current pose = neutral)") {
            Prefs.bump(this, Prefs.CALIBRATE_TOKEN)
            toast("Calibrated")
        }

        // ---- motion profiles ----
        val prefs = Prefs.get(this)
        val current = MotionProfile.fromKey(prefs.getString(Prefs.PROFILE, null))
        root.addView(sectionTitle("Motion profile"))
        root.addView(TextView(this).apply {
            text = "Active: ${current.label}. Choosing a profile rewrites the motion settings below."
            textSize = 12f
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in MotionProfile.values()) {
            row.addView(Button(this).apply {
                text = (if (p == current) "● " else "") + p.label
                isAllCaps = false
                textSize = 12f
                setOnClickListener {
                    p.apply(this@MainActivity)
                    recreate()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
        addButton("Apply iOS-style preset") {
            MotionProfile.IOS.apply(this)
            toast("iOS-style preset applied")
            recreate()
        }

        // ---- motion settings ----
        val d = ParallaxSettings()
        root.addView(sectionTitle("Motion"))
        addSwitch("Motion enabled", Prefs.MOTION_ENABLED, d.motionEnabled)
        addSwitch("Invert direction", Prefs.INVERT, d.invert)
        addSlider("Overall sensitivity", Prefs.SENS, 0.3f, 2f, d.sens, 17) { "%.1f×".format(it) }
        addSlider("Horizontal sensitivity", Prefs.H_SENS, 0.3f, 2f, d.hSens, 17) { "%.1f×".format(it) }
        addSlider("Vertical sensitivity", Prefs.V_SENS, 0.3f, 2f, d.vSens, 17) { "%.1f×".format(it) }
        addSlider("Maximum displacement (% of screen)", Prefs.SHIFT_PCT, 1f, 10f, d.shiftPct, 18) { "%.1f %%".format(it) }
        addSlider("Motion smoothing (higher = softer)", Prefs.SMOOTH_MS, 40f, 400f, d.smoothMs, 36) { "${it.roundToInt()} ms" }
        addSlider("Return to neutral (seconds, 0 = off)", Prefs.RECENTER_SEC, 0f, 30f, d.recenterSec, 30) { if (it < 0.5f) "off" else "${it.roundToInt()} s" }

        root.addView(sectionTitle("Depth"))
        addSlider("Depth separation (gap between layers)", Prefs.DEPTH_CONTRAST, 1.5f, 8f, d.zFar, 13) { "%.1f".format(it) }
        addSlider("Perspective (dolly zoom)", Prefs.PERSPECTIVE, 0f, 3f, d.perspective, 30) { "%.1f×".format(it) }

        root.addView(sectionTitle("Performance"))
        addSlider("Frame cap", Prefs.FPS, 30f, 120f, d.fps.toFloat(), 3) { "${it.roundToInt()} fps" }

        root.addView(sectionTitle("Device report"))
        root.addView(TextView(this).apply {
            text = SensorProbe.report(this@MainActivity)
            typeface = Typeface.MONOSPACE
            textSize = 12f
        })
    }

    private fun setWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, ParallaxWallpaperService::class.java)
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: ActivityNotFoundException) {
                toast("No wallpaper picker found on this device")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        startActivityForResult(Intent.createChooser(intent, "Choose image"), REQ_PICK)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            toast("Importing…")
            Thread {
                val ok = WallpaperStore.importImage(applicationContext, uri)
                runOnUiThread { toast(if (ok) "Image imported" else "Import failed") }
            }.start()
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun addButton(label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    private fun addSwitch(label: String, key: String, def: Boolean) {
        val prefs = Prefs.get(this)
        root.addView(Switch(this).apply {
            text = label
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        })
    }

    private fun addSlider(
        label: String, key: String, min: Float, max: Float, def: Float, steps: Int,
        fmt: (Float) -> String
    ) {
        val prefs = Prefs.get(this)
        fun valueOf(p: Int): Float = min + (max - min) * p / steps

        val title = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        val bar = SeekBar(this).apply { this.max = steps }
        val current = prefs.getFloat(key, def)
        bar.progress = (((current - min) / (max - min)) * steps).roundToInt().coerceIn(0, steps)
        title.text = "$label: ${fmt(valueOf(bar.progress))}"

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = valueOf(progress)
                title.text = "$label: ${fmt(v)}"
                if (fromUser) prefs.edit().putFloat(key, v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(title)
        root.addView(bar)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PICK = 1001
    }
}
