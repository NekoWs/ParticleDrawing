package work.nekow.particledrawing

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.animation.emitter.EmitterSampling
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
