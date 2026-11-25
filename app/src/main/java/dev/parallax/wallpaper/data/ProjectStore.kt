package dev.parallax.wallpaper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.max

/** Persists the layer project (project.json plus the PNG files in the layers folder) and creates layers. */
object ProjectStore {
    private const val PROJECT_FILE = "project.json"
    private const val BASE_FILE = "background.jpg"

    fun layersDir(context: Context): File = File(context.filesDir, "layers").also { it.mkdirs() }

    /**
     * Returns the saved project. If none exists but a single image was imported by an earlier
     * version, that image is presented as a one-layer project (full backwards compatibility).
     */
    fun load(context: Context): WallpaperProject {
        val f = File(context.filesDir, PROJECT_FILE)
        if (f.exists()) {
            try {
                return WallpaperProject.fromJson(JSONObject(f.readText()))
            } catch (t: Throwable) {
                // fall through to legacy handling
            }
        }
        val legacy = WallpaperStore.imageFile(context)
        if (legacy.exists()) {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(legacy.path, b)
            if (b.outWidth > 0 && b.outHeight > 0) {
                return WallpaperProject(
                    b.outWidth, b.outHeight,
                    listOf(LayerSpec(id = "base", name = "Image", file = BASE_FILE, depth = 1f))
                )
            }
        }
        return WallpaperProject.EMPTY
    }

    /**
     * Saves the project. structural = layers added/removed/reordered/toggled (textures must be
     * reloaded); otherwise only numeric parameters changed (cheap update, no reload).
     */
    fun save(context: Context, project: WallpaperProject, structural: Boolean) {
        val tmp = File(context.filesDir, "project.tmp")
        tmp.writeText(project.toJson().toString())
        val dest = File(context.filesDir, PROJECT_FILE)
        if (!tmp.renameTo(dest)) {
            dest.delete()
            tmp.renameTo(dest)
        }
        if (structural) Prefs.bump(context, Prefs.IMAGE_REVISION, Prefs.PARAMS_REVISION)
        else Prefs.bump(context, Prefs.PARAMS_REVISION)
    }

    fun respaceDepths(layers: List<LayerSpec>): List<LayerSpec> {
        val n = layers.size
        if (n == 0) return layers
        if (n == 1) return listOf(layers[0].copy(depth = 1f))
        return layers.mapIndexed { i, l -> l.copy(depth = i / (n - 1f)) }
    }

    /** Replaces everything with a single base image layer. */
    fun resetToSingle(context: Context, w: Int, h: Int) {
        clearLayerFiles(context)
        val spec = LayerSpec(id = "base", name = "Image", file = BASE_FILE, depth = 1f)
        save(context, WallpaperProject(w, h, listOf(spec)), true)
    }

    fun clearAll(context: Context) {
        clearLayerFiles(context)
        File(context.filesDir, BASE_FILE).delete()
        File(context.filesDir, PROJECT_FILE).delete()
        Prefs.bump(context, Prefs.IMAGE_REVISION, Prefs.PARAMS_REVISION)
    }

    fun deleteLayerFile(context: Context, spec: LayerSpec) {
        File(context.filesDir, spec.file).delete()
    }

    /** Replaces the whole project with new layers (back to front); writes PNGs and recycles the bitmaps. */
    fun replaceWithBitmaps(context: Context, w: Int, h: Int, items: List<Triple<String, Bitmap, Boolean>>) {
        clearLayerFiles(context)
        val specs = ArrayList<LayerSpec>()
        for ((i, it) in items.withIndex()) {
            val id = "ai" + i + "_" + System.currentTimeMillis().toString(36)
            FileOutputStream(File(layersDir(context), "$id.png")).use { os ->
                it.second.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            it.second.recycle()
            specs.add(LayerSpec(id = id, name = it.first, file = "layers/$id.png", hasAlpha = it.third))
        }
        save(context, WallpaperProject(w, h, respaceDepths(specs)), true)
    }

    private fun clearLayerFiles(context: Context) {
        layersDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Adds an image (ideally a transparent PNG) as a new front layer, fitted to the canvas. */
    fun addLayerFromUri(context: Context, uri: Uri, name: String): Boolean {
        return try {
            val project = load(context)
            if (project.canvasW <= 0 || project.canvasH <= 0) return false
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            val hasAlpha = src.hasAlpha()

            val cw = project.canvasW
            val ch = project.canvasH
            val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            val k = max(cw.toFloat() / src.width, ch.toFloat() / src.height)
            val m = Matrix().apply {
                postScale(k, k)
                postTranslate((cw - src.width * k) / 2f, (ch - src.height * k) / 2f)
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()

            val id = "l" + System.currentTimeMillis().toString(36)
            FileOutputStream(File(layersDir(context), "$id.png")).use {
                out.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            out.recycle()

            val spec = LayerSpec(id = id, name = name, file = "layers/$id.png", hasAlpha = hasAlpha)
            save(context, project.copy(layers = project.layers + spec), true)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Builds a procedural four-layer scene so the depth engine can be judged without any assets. */
    fun createDemo(context: Context): Boolean {
        return try {
            val w = 1080
            val h = 2400
            clearLayerFiles(context)
            val specs = ArrayList<LayerSpec>()

            fun store(id: String, name: String, bmp: Bitmap, alpha: Boolean) {
                FileOutputStream(File(layersDir(context), "$id.png")).use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bmp.recycle()
                specs.add(LayerSpec(id = id, name = name, file = "layers/$id.png", hasAlpha = alpha))
            }

            // 1. distant background: sky gradient, sun, stars (opaque)
            run {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    intArrayOf(Color.rgb(10, 14, 48), Color.rgb(90, 40, 120), Color.rgb(255, 140, 90)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
                val rnd = Random(7)
                p.color = Color.argb(200, 255, 255, 255)
                for (i in 0 until 90) {
                    c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.5f, 1f + rnd.nextFloat() * 3f, p)
                }
                p.color = Color.rgb(255, 214, 150)
                c.drawCircle(w * 0.5f, h * 0.38f, w * 0.17f, p)
                store("demo_far", "Distant background", bmp, false)
            }

            // 2. background: far mountain ridge
            run {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.rgb(70, 45, 110)
                val path = Path()
                path.moveTo(0f, h * 0.62f)
                val peaks = floatArrayOf(0.50f, 0.42f, 0.55f, 0.38f, 0.52f, 0.46f)
                for (i in peaks.indices) {
                    val x = w * (i + 0.5f) / peaks.size
                    path.lineTo(x, h * peaks[i])
                    path.lineTo(w * (i + 1f) / peaks.size, h * 0.62f)
                }
                path.lineTo(w.toFloat(), h.toFloat())
                path.lineTo(0f, h.toFloat())
                path.close()
                c.drawPath(path, p)
                store("demo_back", "Background", bmp, true)
            }

            // 3. midground: rolling hills with trees and a ring
            run {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.rgb(36, 28, 78)
                val hill = Path()
                hill.moveTo(0f, h * 0.74f)
                hill.quadTo(w * 0.30f, h * 0.62f, w * 0.60f, h * 0.74f)
                hill.quadTo(w * 0.85f, h * 0.82f, w.toFloat(), h * 0.70f)
                hill.lineTo(w.toFloat(), h.toFloat())
                hill.lineTo(0f, h.toFloat())
                hill.close()
                c.drawPath(hill, p)
                p.color = Color.rgb(20, 16, 56)
                val xs = floatArrayOf(0.12f, 0.24f, 0.70f, 0.84f)
                val ys = floatArrayOf(0.70f, 0.67f, 0.73f, 0.69f)
                for (i in xs.indices) {
                    val tree = Path()
                    val cx = w * xs[i]
                    val by = h * ys[i]
                    tree.moveTo(cx, by - h * 0.07f)
                    tree.lineTo(cx - w * 0.05f, by)
                    tree.lineTo(cx + w * 0.05f, by)
                    tree.close()
                    c.drawPath(tree, p)
                }
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.012f
                p.color = Color.argb(160, 255, 200, 160)
                c.drawCircle(w * 0.5f, h * 0.38f, w * 0.26f, p)
                store("demo_mid", "Midground", bmp, true)
            }

            // 4. foreground: big dark shapes at the bottom and a label
            run {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.rgb(8, 6, 24)
                c.drawCircle(w * 0.05f, h * 0.97f, w * 0.34f, p)
                c.drawCircle(w * 0.55f, h * 1.02f, w * 0.30f, p)
                c.drawCircle(w * 0.98f, h * 0.95f, w * 0.36f, p)
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.textSize = w * 0.12f
                c.drawText("FOREGROUND", w * 0.5f, h * 0.60f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.01f
                c.drawRoundRect(w * 0.10f, h * 0.55f, w * 0.90f, h * 0.635f, 40f, 40f, p)
                store("demo_front", "Foreground", bmp, true)
            }

            save(context, WallpaperProject(w, h, respaceDepths(specs)), true)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
