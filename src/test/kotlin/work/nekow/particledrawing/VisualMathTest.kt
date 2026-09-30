package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import work.nekow.particledrawing.core.client.BridgeParticle
import work.nekow.particledrawing.util.VisualMath
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 尺寸口径与长轴朝向换算。
 *
 * 朝向用渲染层同一个四元数构造器验证（`BridgeParticle.orientationQuaternion`）：
 * 局部 X 轴（长轴，quad 宽度那根）转完必须落在请求的方向上——丝线「躺对方向」全靠它。
 */
class VisualMathTest {

    private fun assertAxisAligned(dx: Double, dy: Double, dz: Double) {
        val e = VisualMath.longAxisEulerDegrees(dx, dy, dz)
        val q = Quaternionf()
        BridgeParticle.orientationQuaternion(e, true, q)
        val v = q.transform(Vector3f(1f, 0f, 0f))
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        assertTrue(abs(v.x - dx / len) < 1e-5, "x 分量不符：${v.x} vs ${dx / len}")
        assertTrue(abs(v.y - dy / len) < 1e-5, "y 分量不符：${v.y} vs ${dy / len}")
        assertTrue(abs(v.z - dz / len) < 1e-5, "z 分量不符：${v.z} vs ${dz / len}")
    }

    @Test
    fun `长轴对齐任意方向`() {
        assertAxisAligned(1.0, 0.0, 0.0)
        assertAxisAligned(0.0, 0.0, 1.0)
        assertAxisAligned(1.0, 0.0, 1.0)
        assertAxisAligned(1.0, 2.0, 3.0)
        assertAxisAligned(-2.0, 0.5, 1.5)
        assertAxisAligned(0.3, -4.0, 0.2)
    }

    @Test
    fun `长轴对齐：万向锁方向（±Y）也准确`() {
        assertAxisAligned(0.0, 1.0, 0.0)
        assertAxisAligned(0.0, -1.0, 0.0)
        assertAxisAligned(0.0, 1.0, 0.001)
    }

    @Test
    fun `零向量不动朝向`() {
        val e = VisualMath.longAxisEulerDegrees(0.0, 0.0, 0.0)
        assertEquals(0.0, e[0])
        assertEquals(0.0, e[1])
        assertEquals(0.0, e[2])
    }

    @Test
    fun `世界格 → 编辑器单位：按贴图尺寸系数换算`() {
        // 整宽 = 编辑器尺寸 × 0.2 × 系数 → 编辑器尺寸 = 整宽 / (0.2 × 系数)
        assertEquals(2.0f, VisualMath.editorUnitsForWorldWidth(0.4f, 1f), 1e-6f)
        assertEquals(1.0f, VisualMath.editorUnitsForWorldWidth(0.4f, 2f), 1e-6f)
        assertEquals(5.0f, VisualMath.editorUnitsForWorldWidth(1.0f, 1f), 1e-6f)
    }

    @Test
    fun `世界格尺寸换算后渲染整宽就是请求值`() {
        for (world in listOf(0.05f, 0.4f, 3.0f)) {
            for (texScale in listOf(1f, 2f, 0.5f)) {
                val editor = VisualMath.editorUnitsForWorldWidth(world, texScale)
                val rendered = editor * 2f * VisualMath.EDITOR_TO_MC_SCALE * texScale
                assertEquals(world, rendered, 1e-5f)
            }
        }
    }

    @Test
    fun `分量重载的算例与 Vec3 一致`() {
        val e = VisualMath.longAxisEulerDegrees(1.0, 1.0, 0.0)
        val q = Quaternionf()
        BridgeParticle.orientationQuaternion(e, true, q)
        val dir = Vec3(1.0, 1.0, 0.0).normalize()
        val v = q.transform(Vector3f(1f, 0f, 0f))
        assertEquals(dir.x, v.x.toDouble(), 1e-5)
        assertEquals(dir.y, v.y.toDouble(), 1e-5)
        assertEquals(dir.z, v.z.toDouble(), 1e-5)
    }
}
