package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.client.RotationSlot
import work.nekow.particledrawing.core.client.radialRel
import work.nekow.particledrawing.util.rotateAround
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 编排动画的帧计算：组级倍率对「粒子到轴心的距离」的作用，以及多条旋转指令的累加。
 *
 * 两条都是使用方（球壳类特效）最在意的语义：`scale(3f)` 要把半径 3 的圆变成半径 9；
 * `spin` 与一次性 `rotate` 要能叠加，否则「补一段相位再继续自转」补不上去。
 */
class ProgramFrameTest {

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
