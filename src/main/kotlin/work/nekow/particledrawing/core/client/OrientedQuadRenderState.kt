package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * 支持非等宽四边形的 quad 渲染状态：原版每颗粒子只存一个 float 尺寸，四边形永远是正方形，
 * 画不出定向细长线段。
 *
 * add() 时按层与加入顺序额外记一份宽度（高度仍走原版那个 float）；
 * renderRotatedQuad() 里先在四边形自己的坐标系按宽高各自缩放，再按朝向四元数旋转。
 * 宽度缺失时交回原版，均匀四边形不多算。
 */
class OrientedQuadRenderState : QuadParticleRenderState() {

    /** 单层的宽度队列：数组只增不减地复用，稳态零分配。 */
    private class Widths {
        var values = FloatArray(1024)
        var count = 0

        fun add(width: Float) {
            if (count == values.size) values = values.copyOf(values.size * 2)
            values[count++] = width
        }
    }

    // 按层排队：一次 buildLayer 只画一层，每层的加入顺序就是它顶点生成的顺序
    private val widths = HashMap<SingleQuadParticle.Layer, Widths>()

    // 顶点生成的临时对象，只在渲染线程用，复用以免每颗粒子每帧分配
    private val scratchQ = Quaternionf()
    private val scratchV = Vector3f()

    private var current: Widths? = null
    private var cursor = 0

    /** 下一次 add() 要记的四边形宽度，小于 0 表示均匀；由 BridgeParticle 提交前写入，add() 里消费掉。 */
    var pendingWidth: Float = -1f

    override fun add(
        layer: SingleQuadParticle.Layer,
        x: Float, y: Float, z: Float,
        xRot: Float, yRot: Float, zRot: Float, wRot: Float,
        scale: Float, u0: Float, u1: Float, v0: Float, v1: Float,
        color: Int, lightCoords: Int,
    ) {
        val width = pendingWidth
        pendingWidth = -1f
        widths.getOrPut(layer) { Widths() }.add(width)
        super.add(layer, x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords)
    }

    override fun buildLayer(layer: SingleQuadParticle.Layer, bufferBuilder: VertexConsumer) {
        current = widths[layer]
        cursor = 0
        super.buildLayer(layer, bufferBuilder)
        current = null
    }

    /** 每帧开头原版会调用本方法清存储，宽度队列跟着一起清，没画到的层也不留陈旧数据。 */
    override fun clear() {
        super.clear()
        for (w in widths.values) w.count = 0
        current = null
        cursor = 0
    }

    override fun renderRotatedQuad(
        builder: VertexConsumer,
        x: Float, y: Float, z: Float,
        xRot: Float, yRot: Float, zRot: Float, wRot: Float,
        scale: Float, u0: Float, u1: Float, v0: Float, v1: Float,
        color: Int, lightCoords: Int,
    ) {
        val queue = current
        val index = cursor++
        val width = if (queue != null && index < queue.count) queue.values[index] else -1f
        if (width < 0f || width == scale) {
            super.renderRotatedQuad(builder, x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords)
            return
        }
        scratchQ.set(xRot, yRot, zRot, wRot)
        // 四角与 UV 的对应关系与原版 renderRotatedQuad 完全一致
        vertex(builder, x, y, z, 1f, -1f, width, scale, u1, v1, color, lightCoords)
        vertex(builder, x, y, z, 1f, 1f, width, scale, u1, v0, color, lightCoords)
        vertex(builder, x, y, z, -1f, 1f, width, scale, u0, v0, color, lightCoords)
        vertex(builder, x, y, z, -1f, -1f, width, scale, u0, v1, color, lightCoords)
    }

    /** 写一个角：先在自己坐标系缩放到 (±宽, ±高)，再按朝向旋转、平移到粒子位置。 */
    private fun vertex(
        builder: VertexConsumer,
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, width: Float, height: Float,
        u: Float, v: Float, color: Int, lightCoords: Int,
    ) {
        cornerOffset(nx, ny, width, height, scratchQ, scratchV)
        builder.addVertex(scratchV.x + x, scratchV.y + y, scratchV.z + z)
            .setUv(u, v)
            .setColor(color)
            .setLight(lightCoords)
    }

    companion object {
        /**
         * 一个角相对粒子中心的偏移：先在自己坐标系按宽高各自缩放，再按朝向四元数旋转。
         * 单独拆出来是为了能在不启动 Minecraft 的测试里钉住这个顺序。
         */
        @JvmStatic
        fun cornerOffset(nx: Float, ny: Float, width: Float, height: Float, rotation: Quaternionf, out: Vector3f) {
            out.set(nx * width, ny * height, 0f).rotate(rotation)
        }
    }
}
