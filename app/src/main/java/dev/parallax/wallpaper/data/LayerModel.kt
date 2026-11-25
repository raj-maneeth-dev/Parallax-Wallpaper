package dev.parallax.wallpaper.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One depth layer. All layers of a project share the same canvas, so they line up exactly at the
 * neutral pose and only separate when the camera moves.
 *
 * depth: 0 = far away, 1 = nearest. hMul/vMul scale the motion per axis. scale >= 1 enlarges the
 * layer about the screen centre. perspective scales the subtle dolly zoom. smoothing scales the
 * global smoothing time for this layer.
 */
data class LayerSpec(
    val id: String,
    val name: String,
    val file: String,
    val depth: Float = 1f,
    val hMul: Float = 1f,
    val vMul: Float = 1f,
    val scale: Float = 1f,
    val perspective: Float = 1f,
    val smoothing: Float = 1f,
    val enabled: Boolean = true,
    val hasAlpha: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("file", file)
        put("depth", depth.toDouble())
        put("hMul", hMul.toDouble())
        put("vMul", vMul.toDouble())
        put("scale", scale.toDouble())
        put("perspective", perspective.toDouble())
        put("smoothing", smoothing.toDouble())
        put("enabled", enabled)
        put("hasAlpha", hasAlpha)
    }

    companion object {
        fun fromJson(o: JSONObject): LayerSpec = LayerSpec(
            id = o.getString("id"),
            name = o.optString("name", "Layer"),
            file = o.getString("file"),
            depth = o.optDouble("depth", 1.0).toFloat(),
            hMul = o.optDouble("hMul", 1.0).toFloat(),
            vMul = o.optDouble("vMul", 1.0).toFloat(),
            scale = o.optDouble("scale", 1.0).toFloat(),
            perspective = o.optDouble("perspective", 1.0).toFloat(),
            smoothing = o.optDouble("smoothing", 1.0).toFloat(),
            enabled = o.optBoolean("enabled", true),
            hasAlpha = o.optBoolean("hasAlpha", false)
        )
    }
}

/** Layers are ordered back (index 0) to front (last). */
data class WallpaperProject(
    val canvasW: Int,
    val canvasH: Int,
    val layers: List<LayerSpec>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("canvasW", canvasW)
        put("canvasH", canvasH)
        val arr = JSONArray()
        for (l in layers) arr.put(l.toJson())
        put("layers", arr)
    }

    companion object {
        val EMPTY = WallpaperProject(0, 0, emptyList())

        fun fromJson(o: JSONObject): WallpaperProject {
            val arr = o.getJSONArray("layers")
            val list = ArrayList<LayerSpec>()
            for (i in 0 until arr.length()) list.add(LayerSpec.fromJson(arr.getJSONObject(i)))
            return WallpaperProject(o.optInt("canvasW", 0), o.optInt("canvasH", 0), list)
        }
    }
}
