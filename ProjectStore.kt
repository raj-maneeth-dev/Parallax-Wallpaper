package dev.parallax.wallpaper.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.parallax.wallpaper.ai.Separator
import dev.parallax.wallpaper.data.LayerSpec
import dev.parallax.wallpaper.data.ProjectStore
import dev.parallax.wallpaper.data.WallpaperProject
import kotlin.math.roundToInt

/** Depth editor: select layers, tune depth and motion per layer, reorder, toggle, add; live preview on top. */
class LayerEditorActivity : Activity() {

    private lateinit var preview: ParallaxSurfaceView
    private lateinit var list: LinearLayout
    private var project: WallpaperProject = WallpaperProject.EMPTY
    private var lastSaveMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val dm = resources.displayMetrics
        val ph = (dm.heightPixels * 0.38f).toInt()
        val pw = (ph.toFloat() * dm.widthPixels / dm.heightPixels).toInt()
        preview = ParallaxSurfaceView(this)
        root.addView(preview, LinearLayout.LayoutParams(pw, ph).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallButton("Add layer") { pickLayer() }, weightParams())
        actions.addView(smallButton("Auto-space depths") { autoSpace() }, weightParams())
        actions.addView(smallButton("4-layer demo") {
            if (project.layers.isEmpty()) createDemo()
            else confirm("Replace the current layers with the demo?") { createDemo() }
        }, weightParams())
        root.addView(actions)
        root.addView(smallButton("AI: separate subject from a photo") { startAi() })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
        reload()
    }

    override fun onResume() {
        super.onResume()
        preview.onResume()
    }

    override fun onPause() {
        preview.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        preview.destroy()
        super.onDestroy()
    }

    private fun reload() {
        project = ProjectStore.load(this)
        rebuildList()
    }

    private fun rebuildList() {
        list.removeAllViews()
        if (project.layers.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No layers yet. Import an image on the main screen, or tap \"4-layer demo\"."
                setPadding(0, dp(16), 0, 0)
            })
            return
        }
        list.addView(TextView(this).apply {
            text = "Front layer first. Depth: 0 = far, 1 = near."
            textSize = 12f
        })
        for (i in project.layers.indices.reversed()) addCard(i)
    }

    private fun addCard(index: Int) {
        val spec = project.layers[index]
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0x14000000)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sw = Switch(this).apply {
            text = spec.name
            setTypeface(typeface, Typeface.BOLD)
            isChecked = spec.enabled
            setOnCheckedChangeListener { _, checked ->
                update(index, true, true) { it.copy(enabled = checked) }
            }
        }
        header.addView(sw, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("▲") { move(index, 1) })
        header.addView(smallButton("▼") { move(index, -1) })
        header.addView(smallButton("✕") { confirm("Delete \"${spec.name}\"?") { delete(index) } })
        card.addView(header)

        slider(card, index, "Depth", 0f, 1f, spec.depth, 20, { "%.2f".format(it) }) { l, v -> l.copy(depth = v) }
        slider(card, index, "Horizontal movement", 0f, 3f, spec.hMul, 30, { "%.1f×".format(it) }) { l, v -> l.copy(hMul = v) }
        slider(card, index, "Vertical movement", 0f, 3f, spec.vMul, 30, { "%.1f×".format(it) }) { l, v -> l.copy(vMul = v) }
        slider(card, index, "Scale", 1f, 1.5f, spec.scale, 25, { "%.2f×".format(it) }) { l, v -> l.copy(scale = v) }
        slider(card, index, "Perspective", 0f, 3f, spec.perspective, 30, { "%.1f×".format(it) }) { l, v -> l.copy(perspective = v) }
        slider(card, index, "Smoothing", 0.25f, 3f, spec.smoothing, 55, { "%.2f×".format(it) }) { l, v -> l.copy(smoothing = v) }

        list.addView(card, lp)
    }

    private fun slider(
        parent: LinearLayout, index: Int, label: String, min: Float, max: Float, value: Float,
        steps: Int, fmt: (Float) -> String, change: (LayerSpec, Float) -> LayerSpec
    ) {
        fun valueOf(p: Int): Float = min + (max - min) * p / steps
        val title = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        val bar = SeekBar(this).apply { this.max = steps }
        bar.progress = (((value - min) / (max - min)) * steps).roundToInt().coerceIn(0, steps)
        title.text = "$label: ${fmt(valueOf(bar.progress))}"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = valueOf(progress)
                title.text = "$label: ${fmt(v)}"
                if (fromUser) update(index, false, false) { change(it, v) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                update(index, false, true) { it }
            }
        })
        parent.addView(title)
        parent.addView(bar)
    }

    /** Applies f to one layer and saves (throttled while dragging; always saved when forced). */
    private fun update(index: Int, structural: Boolean, force: Boolean, f: (LayerSpec) -> LayerSpec) {
        if (index !in project.layers.indices) return
        val layers = project.layers.toMutableList()
        layers[index] = f(layers[index])
        project = project.copy(layers = layers)
        val now = SystemClock.uptimeMillis()
        if (structural || force || now - lastSaveMs > 60L) {
            ProjectStore.save(this, project, structural)
            lastSaveMs = now
        }
    }

    private fun move(index: Int, dir: Int) {
        val j = index + dir
        if (j !in project.layers.indices) return
        val layers = project.layers.toMutableList()
        val tmp = layers[index]
        layers[index] = layers[j]
        layers[j] = tmp
        project = project.copy(layers = layers)
        ProjectStore.save(this, project, true)
        rebuildList()
    }

    private fun delete(index: Int) {
        if (index !in project.layers.indices) return
        val layers = project.layers.toMutableList()
        val removed = layers.removeAt(index)
        ProjectStore.deleteLayerFile(this, removed)
        project = project.copy(layers = layers)
        ProjectStore.save(this, project, true)
        rebuildList()
    }

    private fun autoSpace() {
        project = project.copy(layers = ProjectStore.respaceDepths(project.layers))
        ProjectStore.save(this, project, false)
        rebuildList()
    }

    private fun createDemo() {
        toast("Creating demo…")
        Thread {
            val ok = ProjectStore.createDemo(applicationContext)
            runOnUiThread {
                toast(if (ok) "Demo created" else "Demo failed")
                reload()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun pickLayer() {
        if (project.canvasW <= 0) {
            toast("Import a base image on the main screen first, or create the demo")
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        startActivityForResult(Intent.createChooser(intent, "Choose layer image"), REQ_LAYER)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LAYER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            toast("Importing layer…")
            Thread {
                val ok = ProjectStore.addLayerFromUri(
                    applicationContext, uri, "Layer " + (project.layers.size + 1)
                )
                runOnUiThread {
                    toast(if (ok) "Layer added" else "Import failed")
                    reload()
                }
            }.start()
        }
        if (requestCode == REQ_AI && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            busy = true
            toast("Separating, keep the app open…")
            Thread {
                val r = Separator.run(applicationContext, uri, aiModel)
                runOnUiThread {
                    busy = false
                    toast(r.message)
                    reload()
                }
            }.start()
        }
    }

    private var busy = false
    private var aiModel = Separator.Model.PEOPLE

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> ok() }
            .setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    private fun startAi() {
        if (busy) return
        val choose = {
            AlertDialog.Builder(this).setTitle("What is the subject?")
                .setItems(arrayOf("A person", "Person, pet or object")) { _, which ->
                    aiModel = if (which == 0) Separator.Model.PEOPLE else Separator.Model.GENERAL
                    val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    startActivityForResult(Intent.createChooser(intent, "Choose photo"), REQ_AI)
                }.show()
        }
        if (project.layers.isEmpty()) choose()
        else confirm("This replaces all current layers with the separated photo. Continue?") { choose() }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setOnClickListener { onClick() }
        }

    private fun weightParams() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_LAYER = 2001
        private const val REQ_AI = 2002
    }
}
