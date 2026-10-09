package work.nekow.particledrawing

import org.joml.Quaternionf
import org.joml.Vector3f
import work.nekow.particledrawing.core.client.BridgeParticle
import work.nekow.particledrawing.core.client.OrientedQuadRenderState
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.api.Color
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 定向细长线段（非等宽 + 平面内旋转）的几何契约。
 *
 * 编辑器把「相邻两采样之间的一段」画成：位置取中点、四边形朝世界 +Z 再按 rotation 在平面内旋转、
 * 尺寸 [段长, 线宽]。播放端要出同样的形状，靠的是两件事：
 * 1. 四角先在四边形自己的坐标系按 (宽, 高) 各自缩放，再按朝向旋转——顺序反了斜线段会被切成平行四边形；
 * 2. 高度走原版的 quad 尺寸、宽度由渲染状态按粒子记着，两者相等时退回原版（均匀四边形不多算）。
 *
 * 顶点写入本身要 Minecraft 的 VertexConsumer，单测到不了；这里钉住的是喂给它的那份坐标数学。
 */
class OrientedQuadGeometryTest {

    private val q = Quaternionf()
    private val out = Vector3f()

    /** 绕 +Z 在平面内旋转（本用例只用 Z 分量，与 [0,0,角度] 的写法一致）。 */
    private fun spinZ(deg: Double, local: Boolean = true): Quaternionf =
        Quaternionf().also { BridgeParticle.orientationQuaternion(doubleArrayOf(0.0, 0.0, deg), local, it) }

    @Test
    fun `绕 Z 转 90 度后长边落在世界 Y 轴上`() {
        val rot = spinZ(90.0)
        // 局部 (+宽, -高) 这个角：宽 = 段长 1.0（半宽），高 = 线宽一半 0.05
        OrientedQuadRenderState.cornerOffset(1f, -1f, 1.0f, 0.05f, rot, out)
        // 长边转到 +Y（长 1.0），短边转到 +X（宽 0.05）；反序（先转再按世界轴缩放）会得到 (1.0, 0.05)，是斜的
        assertClose(0.05, out.x, "长边旋转后应压在 X 上的只有线宽")
        assertClose(1.0, out.y, "长边应整段落在 Y 轴上")
        assertClose(0.0, out.z, "平面内旋转不该产生 Z 偏移")
    }

    @Test
    fun `未旋转时四角就是轴对齐矩形`() {
        OrientedQuadRenderState.cornerOffset(1f, 1f, 1.0f, 0.05f, spinZ(0.0), out)
        assertClose(1.0, out.x)
        assertClose(0.05, out.y)
        assertClose(0.0, out.z)
    }

    @Test
    fun `绕 Z 转 30 度后长边随角度倾斜`() {
        val rot = spinZ(30.0)
        OrientedQuadRenderState.cornerOffset(1f, 0f, 1.0f, 0.05f, rot, out)
        assertClose(Math.cos(Math.toRadians(30.0)), out.x)
        assertClose(Math.sin(Math.toRadians(30.0)), out.y)
    }

    @Test
    fun `local 与 world 自转空间在只有 Z 分量时一致`() {
        val localOut = Vector3f()
        val worldOut = Vector3f()
        OrientedQuadRenderState.cornerOffset(1f, 1f, 2.0f, 0.1f, spinZ(45.0, local = true), localOut)
        OrientedQuadRenderState.cornerOffset(1f, 1f, 2.0f, 0.1f, spinZ(45.0, local = false), worldOut)
        assertClose(localOut.x.toDouble(), worldOut.x.toDouble())
        assertClose(localOut.y.toDouble(), worldOut.y.toDouble())
        assertClose(localOut.z.toDouble(), worldOut.z.toDouble())
    }

    @Test
    fun `Rotation 为零时不产生额外偏移`() {
        OrientedQuadRenderState.cornerOffset(1f, -1f, 0.5f, 0.02f, Quaternionf(), out)
        assertClose(0.5, out.x)
        assertClose(-0.02, out.y)
    }

    @Test
    fun `粒子状态按轴保存非等宽尺寸且不被外部数组带着走`() {
        val rp = RenderParticle(UUID.randomUUID(), Vec3.ZERO, Color.WHITE, 1f, false, 0, 0)
        val editor = floatArrayOf(1.5f, 0.02f, 1f)
        rp.setScaleArrayDirect(editor)
        assertEquals(1.5f, rp.scaleArray()[0])
        assertEquals(0.02f, rp.scaleArray()[1])
        assertEquals(1f, rp.scaleArray()[2])
        // 标量口读的是长边（与原实现一致，动画程序走标量口）
        assertEquals(1.5f, rp.scale())
        // 传进来的数组被改不该影响粒子（原实现 copyOf，现在就地写自己的数组）
        editor[0] = 9f
        assertEquals(1.5f, rp.scaleArray()[0])
    }

    @Test
    fun `标量尺寸落到 X Y 同值 Z 为一`() {
        val rp = RenderParticle(UUID.randomUUID(), Vec3.ZERO, Color.WHITE, 1f, false, 0, 0)
        rp.setScaleDirect(0.25f)
        // 编辑器粒子模型的 Z 恒为 1，标量只描述 X/Y
        assertEquals(0.25f, rp.scaleArray()[0])
        assertEquals(0.25f, rp.scaleArray()[1])
        assertEquals(1f, rp.scaleArray()[2])
        // 复用同一个数组实例（热路径每帧每颗粒子都写它，不该每帧新建）
        val arr = rp.scaleArray()
        rp.setScaleDirect(0.5f)
        assertTrue(arr === rp.scaleArray())
        assertEquals(0.5f, rp.scaleArray()[0])
        assertEquals(1f, rp.scaleArray()[2])
    }

    private fun assertClose(expected: Double, actual: Float, message: String? = null) =
        assertClose(expected, actual.toDouble(), message)

    private fun assertClose(expected: Double, actual: Double, message: String? = null) {
        assertTrue(abs(expected - actual) < 1e-6, "${message ?: ""} 期望 $expected，实际 $actual")
    }
}
