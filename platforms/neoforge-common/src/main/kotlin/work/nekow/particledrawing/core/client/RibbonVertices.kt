package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.vertex.VertexConsumer
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.RibbonHistory

/** 将同一份带状几何写入旧版即时渲染或新版分层缓冲。 */
internal class RibbonVertices {
    private var geometry = FloatArray(256 * STRIDE)
    private var colors = IntArray(256)
    private var lights = IntArray(256)
    private var size = 0

    fun clear() {
        size = 0
    }

    fun append(
        history: RibbonHistory, head: Vec3, camera: Vec3, width: Float, partialTick: Float,
        u0: Float, u1: Float, v0: Float, v1: Float, color: Int, light: Int,
    ) {
        val alpha = color ushr 24
        if (alpha == 0) return
        val rgb = color and 0xFFFFFF
        fun vertex(position: Vec3, u: Float, v: Float, progress: Float) {
            if (size == colors.size) {
                val capacity = colors.size * 2
                geometry = geometry.copyOf(capacity * STRIDE)
                colors = colors.copyOf(capacity)
                lights = lights.copyOf(capacity)
            }
            val i = size * STRIDE
            geometry[i] = (position.x - camera.x).toFloat()
            geometry[i + 1] = (position.y - camera.y).toFloat()
            geometry[i + 2] = (position.z - camera.z).toFloat()
            geometry[i + 3] = u
            geometry[i + 4] = v
            colors[size] = (alpha * progress).toInt().shl(24) or rgb
            lights[size] = light
            size++
        }

        history.forEachSegment(head, camera, width, partialTick) { a, b ->
            val au = u0 + (u1 - u0) * a.progress
            val bu = u0 + (u1 - u0) * b.progress
            vertex(a.left, au, v1, a.progress)
            vertex(a.right, au, v0, a.progress)
            vertex(b.right, bu, v0, b.progress)
            vertex(b.left, bu, v1, b.progress)
        }
    }

    fun writeTo(builder: VertexConsumer) {
        for (vertex in 0 until size) {
            val i = vertex * STRIDE
            builder.addVertex(geometry[i], geometry[i + 1], geometry[i + 2])
                .setUv(geometry[i + 3], geometry[i + 4]).setColor(colors[vertex]).setLight(lights[vertex])
        }
    }

    companion object {
        private const val STRIDE = 5

        fun color(r: Float, g: Float, b: Float, a: Float): Int =
            (a.coerceIn(0f, 1f) * 255).toInt().shl(24) or
                (r.coerceIn(0f, 1f) * 255).toInt().shl(16) or
                (g.coerceIn(0f, 1f) * 255).toInt().shl(8) or
                (b.coerceIn(0f, 1f) * 255).toInt()
    }
}
