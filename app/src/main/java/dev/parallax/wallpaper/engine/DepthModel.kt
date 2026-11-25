package dev.parallax.wallpaper.engine

/**
 * Pinhole-camera parallax. A layer at distance Z shifts by 1/Z when the camera moves sideways.
 * depth 1 (nearest) sits at Z_NEAR and moves with factor 1; depth 0 (farthest) sits at zFar and
 * moves with factor Z_NEAR / zFar. A larger zFar therefore means a bigger gap between layers.
 */
object DepthModel {
    const val Z_NEAR = 1f

    fun factor(depth: Float, zFar: Float): Float {
        val d = depth.coerceIn(0f, 1f)
        val far = if (zFar < 1.2f) 1.2f else zFar
        val z = far + (Z_NEAR - far) * d
        return Z_NEAR / z
    }
}
