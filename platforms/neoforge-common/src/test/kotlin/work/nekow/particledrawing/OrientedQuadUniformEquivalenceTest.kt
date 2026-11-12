package work.nekow.particledrawing

import org.joml.Quaternionf
import org.joml.Vector3f
import work.nekow.particledrawing.core.client.OrientedQuadRenderState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 均匀四边形（宽高相同）必须和原版 `QuadParticleRenderState.renderQuad` 画出同一组顶点。
 *
 * 原来均匀尺寸会退回原版实现，那条路每颗粒子每帧新建一个 Quaternionf 加四个 Vector3f；
 * 现在两条路都走 [OrientedQuadRenderState.cornerOffset]，这个用例把数值钉住，
 * 保证省分配没有顺带改变几何。
 */
class OrientedQuadUniformEquivalenceTest {

    private val angles = listOf(
        floatArrayOf(0f, 0f, 0f, 1f),
        floatArrayOf(0.2f, -0.3f, 0.1f, 0.9f),
        floatArrayOf(-0.5f, 0.8f, 0.25f, 0.3f),
    )

    @Test
    fun `均匀四边形的四角与原版公式逐点一致`() {
        val out = Vector3f()
        val reference = Vector3f()
        for (q in angles) {
            val rotation = Quaternionf(q[0], q[1], q[2], q[3]).normalize()
            for (scale in floatArrayOf(0.05f, 0.16f, 1.0f, 3.25f)) {
                for (corner in listOf(1f to -1f, 1f to 1f, -1f to 1f, -1f to -1f)) {
                    val (nx, ny) = corner
                    // 均匀路径：宽 = 高 = scale
                    OrientedQuadRenderState.cornerOffset(nx, ny, scale, scale, rotation, out)
                    // 原版 renderVertex：new Vector3f(nx * scale, ny * scale, 0).rotate(rot)
                    reference.set(nx * scale, ny * scale, 0f).rotate(rotation)
                    val where = "q=${q.toList()} scale=$scale corner=$corner"
                    assertEquals(reference.x, out.x, 1e-6f, "X 不一致：$where")
                    assertEquals(reference.y, out.y, 1e-6f, "Y 不一致：$where")
                    assertEquals(reference.z, out.z, 1e-6f, "Z 不一致：$where")
                }
            }
        }
    }
}
