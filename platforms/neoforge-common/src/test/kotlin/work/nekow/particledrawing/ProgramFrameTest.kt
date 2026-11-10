package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.client.RotationSlot
import work.nekow.particledrawing.core.client.PivotFrame
import work.nekow.particledrawing.core.client.radialRel
import work.nekow.particledrawing.util.rotateAround
import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.animation.script.compileFunctionObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * 编排动画的帧计算：组级倍率对粒子到轴心距离的作用，以及多条旋转指令的累加。
 */
class ProgramFrameTest {
    @Test
    fun `局部sin公式无需世界坐标并随游戏时间产生运动`() {
        val expression = assertNotNull(compileFunctionObject("u=i/(n-1);x=8*u;y=sin(2*PI*u-t*0.002);z=0;", emptyList()))
        val regs = expression.allocRegs()
        val stack = expression.allocStack()
        val pivot = PivotFrame(Vec3(40.0, -58.0, 20.5))
        expression.eval(20.0, 81.0, 0.0, regs, stack)
        val first = pivot.apply(Vec3(regs[Reg.X], regs[Reg.Y], regs[Reg.Z]))
        assertEquals(Vec3(42.0, -57.0, 20.5), first)
        expression.eval(20.0, 81.0, 500.0, regs, stack)
        val next = pivot.apply(Vec3(regs[Reg.X], regs[Reg.Y], regs[Reg.Z]))
        assertEquals(42.0, next.x, 1e-9)
        assertEquals(-58.0 + kotlin.math.cos(1.0), next.y, 1e-9)
    }

    @Test
    fun `相同公式输出会随固定或移动锚点整体平移`() {
        val offset = Vec3(4.0, 1.0, 0.0)
        assertEquals(Vec3(44.0, -57.0, 20.0), PivotFrame(Vec3(40.0, -58.0, 20.0)).apply(offset))
        assertEquals(Vec3(54.0, -57.0, 25.0), PivotFrame(Vec3(50.0, -58.0, 25.0)).apply(offset))
    }

    @Test
    fun `实体锚点的世界偏移与局部朝向都会映射公式坐标`() {
        val center = Vec3(40.0, -60.0, 20.0)
        val offset = Vec3(0.0, 2.0, 0.0)
        val point = Vec3(0.0, 0.0, 3.0)
        assertEquals(Vec3(40.0, -58.0, 23.0), PivotFrame(center, offset, 90f, 0f, false).apply(point))
        val rotated = PivotFrame(center, offset, 90f, 0f, true).apply(point)
        assertEquals(37.0, rotated.x, 1e-5)
        assertEquals(-58.0, rotated.y, 1e-5)
        assertEquals(20.0, rotated.z, 1e-5)
    }


    @Test
    fun `组级倍率同时缩放粒子到轴心的距离与视觉尺寸`() {
        val rel = Vec3(3.0, 0.0, 0.0)

        assertEquals(9.0, radialRel(rel, 3f, 1f).x, 1e-9, "scale(3f)：半径 3 → 9")
        assertEquals(3.0, radialRel(rel, 1f, 1f).x, 1e-9, "倍率 1 不动")
        assertEquals(1.5, radialRel(rel, 1f, 0.5f).x, 1e-9, "pulse 呼吸同样作用于半径")
        assertEquals(9.0, radialRel(rel, 2f, 1.5f).x, 1e-9, "两个倍率相乘：3 × (2 × 1.5)")
        assertEquals(3.0, rel.x, 1e-9, "只是取帧，不改写粒子状态")
    }

    @Test
    fun `持续自转与一次性旋转叠加，后一条不覆盖前一条`() {
        val axis = Vec3(0.0, 1.0, 0.0)
        val perTick = 0.01
        val phase = PI / 2
        var rel = Vec3(1.0, 0.0, 0.0)
        val spin = RotationSlot()
        val once = RotationSlot()

        // 先自转 100 tick
        for (tick in 1..100) rel = rel.rotateAround(axis, spin.take(perTick * tick))
        // 第 101 tick 补一段瞬时相位，然后继续自转 50 tick
        for (tick in 101..150) {
            rel = rel.rotateAround(axis, once.take(phase))
            rel = rel.rotateAround(axis, spin.take(perTick * tick))
        }

        assertEquals(perTick * 150 + phase, yawOf(rel), 1e-9, "总角度 = 自转 + 补的相位")
    }

    @Test
    fun `一次性旋转的缓动按每帧增量落地，总和等于终值`() {
        val axis = Vec3(0.0, 1.0, 0.0)
        val target = 1.2
        val slot = RotationSlot()
        var rel = Vec3(1.0, 0.0, 0.0)
        val deltas = ArrayList<Double>()

        for (frame in 1..20) {
            val eased = target * (frame / 20.0) // 线性缓动
            val d = slot.take(eased)
            if (d != 0.0) {
                deltas.add(d)
                rel = rel.rotateAround(axis, d)
            }
        }

        assertEquals(target, yawOf(rel), 1e-9)
        assertEquals(target, deltas.sum(), 1e-9)
        assertTrue(deltas.all { it > 0.0 }, "缓动推进过程中增量应同号")
        assertEquals(0.0, slot.take(target), 1e-12, "同一角度不重复叠加")
    }

    /** 绕 +Y 旋转后的偏航角（MC 约定：绕 y 转 θ 把 +X 送到 (cosθ, 0, −sinθ)）。 */
    private fun yawOf(rel: Vec3): Double = atan2(-rel.z, rel.x)
}
