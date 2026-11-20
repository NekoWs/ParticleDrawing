package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.vertex.VertexConsumer
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleTrailStyle
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.RibbonHistory

/** 将同一份带状几何写入旧版即时渲染或新版分层缓冲。 */
internal class RibbonVertices {
    private var geometry = FloatArray(256 * STRIDE)
    private var colors = IntArray(256)
    private var lights = IntArray(256)
    private var size = 0
    val isEmpty get() = size == 0

    fun clear() {
        size = 0
    }

    fun append(
        history: RibbonHistory, head: Vec3, camera: Vec3, width: Float, partialTick: Float,
        u0: Float, u1: Float, v0: Float, v1: Float, color: Int, light: Int, sides: Int = 0,
        style: ParticleTrailStyle = ParticleTrailStyle.DEFAULT, opacity: Float = 1f,
    ) {
        val alpha = color ushr 24
        val customColor = style.tailColor != null || style.headColor != null
        if ((!customColor && alpha == 0) || history.size < 2 || width <= 0f || opacity <= 0f || style.maxWidth <= 0f) return
        val cost = (history.size - 1) * maxOf(1, sides)
        if (!ParticleGeometryBudget.reserve(cost)) return
        val rgb = color and 0xFFFFFF
        val original = if (customColor) Color.ofInt(rgb shr 16, rgb shr 8 and 255, rgb and 255, alpha) else null
        fun tint(progress: Float): Int {
            if (original == null) return (alpha * progress * opacity).toInt().shl(24) or rgb
            val gradient = style.colorAt(original, progress)
            return color(gradient.r, gradient.g, gradient.b, gradient.a * opacity)
        }
        fun vertex(position: Vec3, u: Float, v: Float, tint: Int) {
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
            colors[size] = tint
            lights[size] = light
            size++
        }

        if (sides > 0) {
            history.forEachTubeSegment(head, width, partialTick, sides, style) { a, b ->
                val au = u0 + (u1 - u0) * a.progress
                val bu = u0 + (u1 - u0) * b.progress
                val ac = tint(a.progress); val bc = tint(b.progress)
                for (i in 0 until sides) {
                    val next = (i + 1) % sides
                    val low = v0 + (v1 - v0) * (i.toFloat() / sides)
                    val high = v0 + (v1 - v0) * ((i + 1f) / sides)
                    vertex(a.vertices[next], au, high, ac)
                    vertex(a.vertices[i], au, low, ac)
                    vertex(b.vertices[i], bu, low, bc)
                    vertex(b.vertices[next], bu, high, bc)
                }
            }
            return
        }
        history.forEachSegment(head, camera, width, partialTick, style) { a, b ->
            val au = u0 + (u1 - u0) * a.progress
            val bu = u0 + (u1 - u0) * b.progress
            val ac = tint(a.progress); val bc = tint(b.progress)
            vertex(a.left, au, v1, ac)
            vertex(a.right, au, v0, ac)
            vertex(b.right, bu, v0, bc)
            vertex(b.left, bu, v1, bc)
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
