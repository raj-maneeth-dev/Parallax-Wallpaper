package dev.parallax.wallpaper.ai

import kotlin.math.floor

/** Classical hole filling (push-pull pyramid). Gives a smooth, blurry fill: fine for sky, walls and gradients. */
internal object Inpaint {

    /** Bilinear sample of an interleaved float array with n channels. */
    fun bil(a: FloatArray, w: Int, h: Int, fx: Float, fy: Float, ch: Int, n: Int): Float {
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        fun at(x: Int, y: Int): Float = a[(y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)) * n + ch]
        val top = at(x0, y0) * (1f - tx) + at(x0 + 1, y0) * tx
        val bot = at(x0, y0 + 1) * (1f - tx) + at(x0 + 1, y0 + 1) * tx
        return top * (1f - ty) + bot * ty
    }

    /** Fills every hole pixel from the known pixels. Returns RGB floats (0..1), 3 per pixel. */
    fun fill(argb: IntArray, w: Int, h: Int, hole: BooleanArray): FloatArray {
        val cols = ArrayList<FloatArray>()
        val wts = ArrayList<FloatArray>()
        val dw = ArrayList<Int>()
        val dh = ArrayList<Int>()
        var c = FloatArray(w * h * 3)
        var k = FloatArray(w * h)
        for (i in 0 until w * h) if (!hole[i]) {
            val p = argb[i]
            c[i * 3] = (p shr 16 and 255) / 255f
            c[i * 3 + 1] = (p shr 8 and 255) / 255f
            c[i * 3 + 2] = (p and 255) / 255f
            k[i] = 1f
        }
        cols.add(c); wts.add(k); dw.add(w); dh.add(h)
        var cw = w
        var ch = h
        // pull: average known pixels into coarser levels
        while (cw > 1 || ch > 1) {
            val nw = (cw + 1) / 2
            val nh = (ch + 1) / 2
            val nc = FloatArray(nw * nh * 3)
            val nk = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var sw = 0f; var r = 0f; var g = 0f; var b = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val sx = 2 * x + dx
                    val sy = 2 * y + dy
                    if (sx < cw && sy < ch) {
                        val i = sy * cw + sx
                        val q = k[i]
                        sw += q; r += c[i * 3] * q; g += c[i * 3 + 1] * q; b += c[i * 3 + 2] * q
                    }
                }
                if (sw > 0f) {
                    val o = y * nw + x
                    nc[o * 3] = r / sw; nc[o * 3 + 1] = g / sw; nc[o * 3 + 2] = b / sw
                    nk[o] = 1f
                }
            }
            c = nc; k = nk; cw = nw; ch = nh
            cols.add(c); wts.add(k); dw.add(cw); dh.add(ch)
        }
        val top = cols.size - 1
        if (wts[top][0] == 0f) java.util.Arrays.fill(cols[top], 0.5f) // nothing known at all
        // push: fill unknown pixels from the next coarser level
        for (l in top - 1 downTo 0) {
            val fc = cols[l]
            val fk = wts[l]
            val cc = cols[l + 1]
            val w0 = dw[l]
            val h0 = dh[l]
            val w1 = dw[l + 1]
            val h1 = dh[l + 1]
            for (y in 0 until h0) for (x in 0 until w0) {
                val i = y * w0 + x
                if (fk[i] >= 1f) continue
                val fx = (x + 0.5f) / 2f - 0.5f
                val fy = (y + 0.5f) / 2f - 0.5f
                for (q in 0..2) fc[i * 3 + q] = bil(cc, w1, h1, fx, fy, q, 3)
            }
        }
        return cols[0]
    }

    /** Square dilation by radius r. */
    fun dilate(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        val out = BooleanArray(w * h)
        dilatePass(a, tmp, w, h, 1, w, r)
        dilatePass(tmp, out, h, w, w, 1, r)
        return out
    }

    private fun dilatePass(src: BooleanArray, dst: BooleanArray, n: Int, lines: Int, step: Int, lineStep: Int, r: Int) {
        for (l in 0 until lines) {
            val base = l * lineStep
            var cnt = 0
            for (i in 0 until minOf(r, n)) if (src[base + i * step]) cnt++
            for (i in 0 until n) {
                val add = i + r
                if (add < n && src[base + add * step]) cnt++
                val rem = i - r - 1
                if (rem >= 0 && src[base + rem * step]) cnt--
                dst[base + i * step] = cnt > 0
            }
        }
    }

    /** Box blur by radius r (window clipped at the borders, so edges keep their weight). */
    fun blur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r < 1) return a
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        blurPass(a, tmp, w, h, 1, w, r)
        blurPass(tmp, out, h, w, w, 1, r)
        return out
    }

    private fun blurPass(src: FloatArray, dst: FloatArray, n: Int, lines: Int, step: Int, lineStep: Int, r: Int) {
        for (l in 0 until lines) {
            val base = l * lineStep
            var sum = 0f
            for (i in 0 until minOf(r, n)) sum += src[base + i * step]
            for (i in 0 until n) {
                val add = i + r
                if (add < n) sum += src[base + add * step]
                val rem = i - r - 1
                if (rem >= 0) sum -= src[base + rem * step]
                val cnt = minOf(n - 1, i + r) - maxOf(0, i - r) + 1
                dst[base + i * step] = sum / cnt
            }
        }
    }
}
