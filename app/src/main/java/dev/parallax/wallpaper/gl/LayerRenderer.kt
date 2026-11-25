package dev.parallax.wallpaper.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import dev.parallax.wallpaper.data.LayerSpec
import dev.parallax.wallpaper.data.ParallaxSettings
import dev.parallax.wallpaper.engine.DepthModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.max

/**
 * Draws a stack of full-canvas textured quads back to front.
 *
 * Camera model: each layer shifts by (tilt x maxShift x depthFactor x axisMultiplier), where
 * depthFactor = 1/Z (see DepthModel), so near layers move more than far ones. A small dolly zoom
 * (scale grows slightly with tilt, more for near layers) adds perspective.
 *
 * No-gap guarantee: ALL layers share one base zoom, derived from the largest displacement any
 * layer can reach. Layers therefore stay perfectly aligned at the neutral pose, and no layer can
 * ever slide far enough to expose its edge (dynamic and user scale only ever enlarge).
 */
class LayerRenderer {
    var maxTextureSize = 2048
        private set

    private class Gpu(var spec: LayerSpec, val texture: Int)

    private val layers = ArrayList<Gpu>()
    private var canvasW = 0
    private var canvasH = 0

    private var program = 0
    private var aPos = 0
    private var uScale = 0
    private var uOffset = 0
    private var uTex = 0

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    val layerCount: Int get() = layers.size

    fun specAt(i: Int): LayerSpec = layers[i].spec

    fun init() {
        val tmp = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, tmp, 0)
        maxTextureSize = tmp[0]

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uOffset = GLES20.glGetUniformLocation(program, "uOffset")
        uTex = GLES20.glGetUniformLocation(program, "uTex")

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    fun setCanvas(w: Int, h: Int) {
        canvasW = w
        canvasH = h
    }

    fun setViewport(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
    }

    fun clearLayers() {
        for (g in layers) {
            GLES20.glDeleteTextures(1, intArrayOf(g.texture), 0)
        }
        layers.clear()
    }

    /** Uploads the bitmap as the next (frontmost so far) layer. The caller may recycle it after. */
    fun addLayer(spec: LayerSpec, bitmap: Bitmap) {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        layers.add(Gpu(spec, t[0]))
    }

    /** Applies changed numeric parameters to already-uploaded layers (matched by id). */
    fun updateSpecs(specs: List<LayerSpec>): Boolean {
        var all = true
        for (g in layers) {
            val n = specs.firstOrNull { it.id == g.spec.id }
            if (n != null) g.spec = n else all = false
        }
        return all
    }

    /** posX / posY: smoothed normalised tilt per layer (index = layer order), each in -1..1. */
    fun draw(viewW: Int, viewH: Int, s: ParallaxSettings, posX: FloatArray, posY: FloatArray) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (layers.isEmpty() || canvasW <= 0 || canvasH <= 0 || viewW <= 0 || viewH <= 0) return

        val shiftFrac = s.shiftPct / 100f

        // Largest displacement any layer can reach, as a fraction of the screen.
        var fMax = 0f
        for (g in layers) {
            val k = DepthModel.factor(g.spec.depth, s.zFar)
            fMax = max(fMax, shiftFrac * k * max(g.spec.hMul, g.spec.vMul))
        }
        val base = 1f + 2f * fMax
        val canvasAspect = canvasW.toFloat() / canvasH
        val viewAspect = viewW.toFloat() / viewH
        val baseX = base * max(1f, canvasAspect / viewAspect)
        val baseY = base * max(1f, viewAspect / canvasAspect)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertices)

        for (i in layers.indices) {
            val g = layers[i]
            val spec = g.spec
            val px = if (i < posX.size) posX[i] else 0f
            val py = if (i < posY.size) posY[i] else 0f
            val k = DepthModel.factor(spec.depth, s.zFar)

            val dolly = 1f + spec.perspective * s.perspective * k * 0.01f * hypot(px, py)
            val sc = max(1f, spec.scale) * dolly
            val scaleX = baseX * sc
            val scaleY = baseY * sc
            val offX = 2f * px * shiftFrac * k * spec.hMul
            val offY = 2f * py * shiftFrac * k * spec.vMul

            if (i > 0 && spec.hasAlpha) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            } else {
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, g.texture)
            GLES20.glUniform2f(uScale, scaleX, scaleY)
            GLES20.glUniform2f(uOffset, offX, offY)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        clearLayers()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }

    companion object {
        private const val VERTEX_SHADER = """
attribute vec2 aPos;
uniform vec2 uScale;
uniform vec2 uOffset;
varying vec2 vUV;
void main() {
    vUV = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
}
"""
        private const val FRAGMENT_SHADER = """
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
void main() {
    gl_FragColor = texture2D(uTex, vUV);
}
"""
    }
}
