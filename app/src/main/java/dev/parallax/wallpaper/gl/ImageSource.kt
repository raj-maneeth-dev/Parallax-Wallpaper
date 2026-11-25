package dev.parallax.wallpaper.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import dev.parallax.wallpaper.data.LayerSpec
import java.io.File
import kotlin.math.max

/** Decodes layer images sized for this screen, and builds the built-in single-layer test scene. */
object ImageSource {

    val TEST_SPEC = LayerSpec(id = "test", name = "Test scene", file = "", depth = 1f)

    fun decodeLayer(
        context: Context, spec: LayerSpec, viewW: Int, viewH: Int, maxTex: Int
    ): Bitmap? {
        val file = File(context.filesDir, spec.file)
        if (!file.exists()) return null
        return try {
            decode(file.path, viewW, viewH, maxTex)
        } catch (t: Throwable) {
            null
        }
    }

    private fun decode(path: String, viewW: Int, viewH: Int, maxTex: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode at the lowest power-of-two reduction that still has ~25 % more pixels than needed.
        val needScale = max(viewW.toFloat() / bounds.outWidth, viewH.toFloat() / bounds.outHeight) * 1.25f
        var sample = 1
        val limit = (1f / needScale).toInt()
        while (sample * 2 <= limit) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(path, opts) ?: return null

        val longest = max(bmp.width, bmp.height)
        if (longest > maxTex) {
            val k = maxTex.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * k).toInt().coerceAtLeast(1),
                (bmp.height * k).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    /** A high-contrast scene (gradient, grid, rings) that makes parallax motion obvious. */
    fun testScene(w: Int, h: Int): Bitmap {
        val width = w.coerceAtLeast(64)
        val height = h.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.rgb(20, 24, 60), Color.rgb(120, 50, 140), Color.rgb(250, 120, 90)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null

        p.color = Color.argb(70, 255, 255, 255)
        p.strokeWidth = width / 300f + 1f
        val step = width / 8f
        var x = 0f
        while (x <= width) { c.drawLine(x, 0f, x, height.toFloat(), p); x += step }
        var y = 0f
        while (y <= height) { c.drawLine(0f, y, width.toFloat(), y, p); y += step }

        p.style = Paint.Style.STROKE
        p.strokeWidth = width / 60f
        p.color = Color.argb(200, 255, 255, 255)
        val cx = width / 2f
        val cy = height / 2f
        var r = width / 8f
        while (r < width / 1.5f) { c.drawCircle(cx, cy, r, p); r += width / 6f }

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.textSize = width / 9f
        p.color = Color.WHITE
        c.drawText("PARALLAX TEST", cx, cy + p.textSize / 3f, p)
        return bmp
    }
}
