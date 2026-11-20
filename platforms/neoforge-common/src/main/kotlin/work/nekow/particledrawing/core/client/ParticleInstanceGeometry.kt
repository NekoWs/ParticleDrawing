package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.math.Vec3
import java.nio.ByteBuffer

/** 静态顶点由整批实例共用。网格沿用普通粒子的法线、尺寸和每面 UV 约定。 */
internal object ParticleInstanceGeometry {
    const val STRIDE = 8 * Float.SIZE_BYTES

    fun count(model: ParticleModelRenderer.Model?): Int = model?.let { it.faces.size * 3 } ?: 4

    fun write(model: ParticleModelRenderer.Model?, out: ByteBuffer) {
        fun vertex(pos: Vec3, normal: Vec3, u: Float, v: Float) {
            out.putFloat(pos.x.toFloat()).putFloat(pos.y.toFloat()).putFloat(pos.z.toFloat())
            out.putFloat(normal.x.toFloat()).putFloat(normal.y.toFloat()).putFloat(normal.z.toFloat())
            out.putFloat(u).putFloat(v)
        }
        if (model == null) {
            val lowerLeft = Vec3(-1.0, -1.0, 0.0)
            val lowerRight = Vec3(1.0, -1.0, 0.0)
            val upperLeft = Vec3(-1.0, 1.0, 0.0)
            val upperRight = Vec3(1.0, 1.0, 0.0)
            vertex(lowerLeft, Vec3.ZERO, 0f, 1f)
            vertex(lowerRight, Vec3.ZERO, 1f, 1f)
            vertex(upperLeft, Vec3.ZERO, 0f, 0f)
            vertex(upperRight, Vec3.ZERO, 1f, 0f)
        } else {
            for ((triangle, normal) in model.faces) {
                vertex(triangle.a, normal, 0f, 1f)
                vertex(triangle.b, normal, 1f, 1f)
                vertex(triangle.c, normal, 0f, 0f)
            }
        }
        out.flip()
    }
}
