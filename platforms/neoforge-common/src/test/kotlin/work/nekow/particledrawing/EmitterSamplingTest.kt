package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.client.EmitterSampling
import work.nekow.particledrawing.core.client.MovableAnchorSamples
import work.nekow.particledrawing.core.client.ScaleLedger
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 发射器采样与组缩放账本的纯逻辑：抖动偏移的确定性与方向、移动锚点的相邻样本插值、
 * 组缩放账本从执行起点的合成倍率出发。
 */
class EmitterSamplingTest {

    @Test
    fun `同一种子与序号得到同一偏移，不同序号彼此不同`() {
        val seed = EmitterSampling.seedOf(UUID.randomUUID())
        val a1 = EmitterSampling.offset(seed, 7L, Vec3(1.0, 0.0, 0.0), 0.045, 0.0)
        val a2 = EmitterSampling.offset(seed, 7L, Vec3(1.0, 0.0, 0.0), 0.045, 0.0)
        assertEquals(a1, a2, "同一颗的偏移必须可复现（录屏/判读）")

        val b = EmitterSampling.offset(seed, 8L, Vec3(1.0, 0.0, 0.0), 0.045, 0.0)
        assertTrue(a1.distanceTo(b) > 1e-6, "相邻两颗不该拿到同一个偏移")

        val other = EmitterSampling.offset(EmitterSampling.seedOf(UUID.randomUUID()), 7L, Vec3(1.0, 0.0, 0.0), 0.045, 0.0)
        assertTrue(a1.distanceTo(other) > 1e-6, "不同发射器的第 7 颗也该不同")
    }

    @Test
    fun `抖动落在垂直运动方向的圆盘内，不改变沿轴分量`() {
        val seed = 12345L
        val dir = Vec3(1.0, 0.0, 0.0)
        for (i in 0 until 200) {
            val o = EmitterSampling.offset(seed, i.toLong(), dir, 0.05, 0.0)
            assertEquals(0.0, o.x, 1e-9, "垂直方向的抖动不该带来沿轴位移")
            assertTrue(o.length() <= 0.05 + 1e-9, "抖动半径超了：${o.length()}")
        }
    }

    @Test
    fun `前后偏移沿运动方向，且与抖动叠加`() {
        val dir = Vec3(0.0, 0.0, 1.0)
        val back = EmitterSampling.offset(1L, 0L, dir, 0.0, -0.3)
        assertEquals(-0.3, back.z, 1e-9, "负偏移 = 往运动反方向挪")
        val fwd = EmitterSampling.offset(1L, 0L, dir, 0.0, 0.2)
        assertEquals(0.2, fwd.z, 1e-9)

        val both = EmitterSampling.offset(1L, 3L, dir, 0.05, -0.3)
        assertEquals(-0.3, both.z, 1e-9)
        assertTrue(abs(both.x) > 0.0 || abs(both.y) > 0.0, "抖动分量仍在")
    }

    @Test
    fun `零方向也能给出稳定的抖动平面`() {
        val a = EmitterSampling.offset(7L, 1L, Vec3.ZERO, 0.1, 0.0)
        val b = EmitterSampling.offset(7L, 1L, Vec3.ZERO, 0.1, 0.0)
        assertEquals(a, b)
        assertTrue(a.length() <= 0.1 + 1e-9)
    }

    @Test
    fun `两条样本之间按 partialTick 线性插值`() {
        val s = MovableAnchorSamples()
        val t0 = 1_000_000_000L
        s.onSample(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), t0)
        s.onSample(Vec3(2.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), t0 + 50_000_000L)

        assertEquals(0.0, s.resolve(0f, t0 + 50_000_000L)!!.x, 1e-9, "partialTick=0 时在上一位置")
        assertEquals(1.0, s.resolve(0.5f, t0 + 50_000_000L)!!.x, 1e-9)
        assertEquals(2.0, s.resolve(1f, t0 + 50_000_000L)!!.x, 1e-9, "partialTick=1 时在当前位置")
    }

    @Test
    fun `瞬移按跳变处理：两条样本不再拉开一段扫掠`() {
        val s = MovableAnchorSamples()
        val t = 2_000_000_000L
        s.onSample(Vec3(0.0, 0.0, 0.0), Vec3.ZERO, t)
        s.onSample(Vec3(500.0, 0.0, 0.0), Vec3.ZERO, t + 50_000_000L)

        assertEquals(500.0, s.resolve(0f, t + 50_000_000L)!!.x, 1e-9, "partialTick=0 就该在新位置")
        assertEquals(500.0, s.resolve(1f, t + 50_000_000L)!!.x, 1e-9)
    }

    @Test
    fun `断流时停在最后位置，恢复后的第一条按跳变处理`() {
        val s = MovableAnchorSamples()
        val t = 3_000_000_000L
        s.onSample(Vec3(0.0, 0.0, 0.0), Vec3.ZERO, t)
        s.onSample(Vec3(1.0, 0.0, 0.0), Vec3.ZERO, t + 50_000_000L)

        // 断流 500ms：不外推、停在 1.0
        val staleNow = t + 50_000_000L + 500_000_000L
        assertEquals(1.0, s.resolve(0.9f, staleNow)!!.x, 1e-9)

        // 恢复：新样本不再从旧的 1.0 扫过去
        s.onSample(Vec3(1.6, 0.0, 0.0), Vec3.ZERO, staleNow)
        assertEquals(1.6, s.resolve(0f, staleNow)!!.x, 1e-9)
        assertEquals(1.6, s.resolve(1f, staleNow)!!.x, 1e-9)
    }

    @Test
    fun `还没有样本时不发射，速度方向被记下来`() {
        val s = MovableAnchorSamples()
        assertTrue(!s.hasSample())
        assertTrue(s.resolve(0.5f, 0L) == null)
        s.onSample(Vec3.ZERO, Vec3(0.0, 0.0, 2.0), 1L)
        assertTrue(s.hasSample())
        assertEquals(1.0, s.direction.z, 1e-9)
        assertTrue(s.resolve(0.5f, 1L) != null)
    }

    @Test
    fun `相对缩放从执行起点相乘，可先缩到 0 再长回 1`() {
        val ledger = ScaleLedger()
        // 第一条：从 1 缩到 0.01（瞬时）
        var target = ledger.begin(1f, 0.01f, absolute = false)
        assertEquals(0.01f, target, 1e-6f)
        var current = ledger.valueAt(target, 1f)
        assertEquals(0.01f, current, 1e-6f)

        // 第二条：从 0.01 乘 100 → 终点是 1
        val ledger2 = ScaleLedger()
        target = ledger2.begin(current, 100f, absolute = false)
        assertEquals(1f, target, 1e-6f)
        assertEquals(0.505f, ledger2.valueAt(target, 0.5f), 1e-6f, "中点线性（缓动由调用方给）")
        assertEquals(1f, ledger2.valueAt(target, 1f), 1e-6f)
    }

    @Test
    fun `绝对缩放以当前倍率为起点：从 1_5 倍退场不会先跳回 1`() {
        val ledger = ScaleLedger()
        val target = ledger.begin(1.5f, 0f, absolute = true)
        assertEquals(1.5f, ledger.valueAt(target, 0f), 1e-6f, "起点是当时的 1.5 倍")
        assertEquals(0.75f, ledger.valueAt(target, 0.5f), 1e-6f)
        assertEquals(0f, ledger.valueAt(target, 1f), 1e-6f, "0 = 完全收起")
    }

    @Test
    fun `执行起点只捕获一次：同一条指令的每帧调用不会漂移`() {
        val ledger = ScaleLedger()
        val target = ledger.begin(1f, 3f, absolute = false)
        assertEquals(3f, target, 1e-6f)
        // 中间帧把「当前倍率」传成已推进的值，起点仍必须是第一帧捕获的 1
        assertEquals(3f, ledger.begin(2.4f, 3f, absolute = false), 1e-6f)
        assertEquals(2f, ledger.valueAt(target, 0.5f), 1e-6f)
    }
}
