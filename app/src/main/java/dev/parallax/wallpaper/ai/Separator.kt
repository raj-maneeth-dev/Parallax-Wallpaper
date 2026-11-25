package dev.parallax.wallpaper.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import dev.parallax.wallpaper.data.ProjectStore
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 2: photo to two layers. Segment the subject (MediaPipe, offline model from assets), turn the
 * confidence into a soft alpha, cut the subject out, and rebuild the background behind it with a
 * classical push-pull fill. The result replaces the project (back: background, front: subject).
 */
object Separator {
    enum class Model(val asset: String) {
        PEOPLE("selfie_segmenter.tflite"),
        GENERAL("deeplab_v3.tflite")
    }

    class Result(val ok: Boolean, val message: String)

    private class Mask(val w: Int, val h: Int, val v: FloatArray)

    private const val MAX_SIDE = 2400

    fun run(context: Context, uri: Uri, model: Model): Result {
        try {
            context.assets.open(model.asset).close()
        } catch (t: Throwable) {
            return Result(false, "Model " + model.asset + " is missing from the app (check the CI download step)")
        }
        return try {
            val src = decode(context, uri) ?: return Result(false, "Could not read the image")
            val w = src.width
            val h = src.height
            val mask = segment(context, src, model.asset)

            // soft alpha: smoothstep of the confidence
            val alpha = ByteArray(w * h)
            var inside = 0
            for (y in 0 until h) {
                val fy = (y + 0.5f) * mask.h / h - 0.5f
                for (x in 0 until w) {
                    val c = Inpaint.bil(mask.v, mask.w, mask.h, (x + 0.5f) * mask.w / w - 0.5f, fy, 0, 1)
                    val t = ((c - 0.35f) / 0.35f).coerceIn(0f, 1f)
                    val a = (t * t * (3f - 2f * t) * 255f + 0.5f).toInt()
                    alpha[y * w + x] = a.toByte()
                    if (a > 127) inside++
                }
            }
            val frac = inside.toFloat() / (w * h)
            if (frac < 0.01f) return Result(false, "No subject found. Try the other option or another photo")
            if (frac > 0.9f) return Result(false, "The subject fills almost the whole photo, nothing to reveal behind it")

            val pix = IntArray(w * h)
            src.getPixels(pix, 0, w, 0, 0, w, h)
            val fgPix = IntArray(w * h) { (pix[it] and 0xFFFFFF) or ((alpha[it].toInt() and 255) shl 24) }
            val bgPix = reconstruct(src, pix, alpha, w, h)
            src.recycle()

            val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fg.setPixels(fgPix, 0, w, 0, 0, w, h)
            val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bg.setPixels(bgPix, 0, w, 0, 0, w, h)
            ProjectStore.replaceWithBitmaps(
                context, w, h,
                listOf(Triple("Background", bg, false), Triple("Subject", fg, true))
            )
            Result(true, "Done: Subject + Background layers created")
        } catch (t: Throwable) {
            Result(false, "Failed: " + (t.message ?: t.javaClass.simpleName).take(120))
        }
    }

    private fun segment(context: Context, bmp: Bitmap, asset: String): Mask {
        val opts = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(asset).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        val seg = ImageSegmenter.createFromOptions(context, opts)
        try {
            val masks = seg.segment(BitmapImageBuilder(bmp).build()).confidenceMasks().get()
            if (masks.isEmpty()) throw IllegalStateException("model returned no mask")
            val first = masks[0]
            val v = floats(first)
            // one mask: foreground probability. Several masks: index 0 is background, so subject = 1 - it.
            if (masks.size > 1) for (i in v.indices) v[i] = 1f - v[i]
            return Mask(first.width, first.height, v)
        } finally {
            seg.close()
        }
    }

    private fun floats(m: MPImage): FloatArray {
        val bb = ByteBufferExtractor.extract(m)
        bb.rewind()
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** Background without the subject: dilated hole, push-pull fill at reduced size, blended back in. */
    private fun reconstruct(src: Bitmap, pix: IntArray, alpha: ByteArray, w: Int, h: Int): IntArray {
        val s = min(1f, 1024f / max(w, h))
        val sw = max(1, (w * s).toInt())
        val sh = max(1, (h * s).toInt())
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = IntArray(sw * sh)
        scaled.getPixels(sp, 0, sw, 0, 0, sw, sh)
        if (scaled !== src) scaled.recycle()

        val raw = BooleanArray(sw * sh)
        for (y in 0 until sh) {
            val sy = min(h - 1, (y / s).toInt())
            for (x in 0 until sw) {
                val sx = min(w - 1, (x / s).toInt())
                raw[y * sw + x] = (alpha[sy * w + sx].toInt() and 255) > 16
            }
        }
        val r = max(3, (max(sw, sh) * 0.012f).toInt())
        val hole = Inpaint.dilate(raw, sw, sh, r)
        val filled = Inpaint.fill(sp, sw, sh, hole)
        val wt = Inpaint.blur(FloatArray(sw * sh) { if (hole[it]) 1f else 0f }, sw, sh, r / 2)

        val out = IntArray(pix.size) { pix[it] or (0xFF shl 24) }
        for (y in 0 until h) {
            val fy = (y + 0.5f) * sh / h - 0.5f
            for (x in 0 until w) {
                val fx = (x + 0.5f) * sw / w - 0.5f
                val k = Inpaint.bil(wt, sw, sh, fx, fy, 0, 1)
                if (k < 0.004f) continue
                val o = pix[y * w + x]
                val nr = ((o shr 16 and 255) * (1f - k) + Inpaint.bil(filled, sw, sh, fx, fy, 0, 3) * 255f * k).toInt().coerceIn(0, 255)
                val ng = ((o shr 8 and 255) * (1f - k) + Inpaint.bil(filled, sw, sh, fx, fy, 1, 3) * 255f * k).toInt().coerceIn(0, 255)
                val nb = ((o and 255) * (1f - k) + Inpaint.bil(filled, sw, sh, fx, fy, 2, 3) * 255f * k).toInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    /** Decodes the picked image, EXIF-corrected, longest side at most MAX_SIDE. */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(-90f)
        }
        val k = min(1f, MAX_SIDE.toFloat() / max(bmp.width, bmp.height))
        if (k < 1f) m.postScale(k, k)
        if (m.isIdentity) return bmp
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
