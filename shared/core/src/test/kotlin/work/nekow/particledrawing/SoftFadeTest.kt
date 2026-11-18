package work.nekow.particledrawing

import work.nekow.particledrawing.api.SoftFade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 软粒子采样点与淡出权重。 */
class SoftFadeTest {

    @Test
    fun `七个采样点中心为零且沿三轴对称`() {
        val out = DoubleArray(SoftFade.FLOATS)
        SoftFade.sampleOffsets(0.5, out)
        assertEquals(0.0, out[0], 1e-9)
        assertEquals(0.0, out[1], 1e-9)
        assertEquals(0.0, out[2], 1e-9)
        // +x 与 -x 对称
        assertEquals(0.5, out[3], 1e-9)
        assertEquals(-0.5, out[6], 1e-9)
        // 每轴只有一个分量非零
        for (i in 1 until SoftFade.SAMPLES) {
            val base = i * 3
            val nonZero = (0..2).count { kotlin.math.abs(out[base + it]) > 1e-9 }
            assertEquals(1, nonZero, "第 $i 个采样点只应在一个轴上偏移")
        }
    }

    @Test
    fun `完全没被挡时不淡出`() {
        assertEquals(1.0, SoftFade.factor(0), 1e-9)
    }

    @Test
    fun `被挡越多越透明`() {
        val none = SoftFade.factor(1)
        val half = SoftFade.factor(3)
        val all = SoftFade.factor(SoftFade.SAMPLES)
        assertTrue(none > half, "1 个采样被挡应比 3 个更可见")
        assertTrue(half > all, "全被挡应最透明")
        assertTrue(all >= 0.0)
    }

    @Test
    fun `强度为零时不淡出`() {
        assertEquals(1.0, SoftFade.factor(SoftFade.SAMPLES, strength = 0.0), 1e-9)
    }

    @Test
    fun `采样半径有下限与上限`() {
        assertEquals(0.0, SoftFade.radiusOf(0.0), 1e-9)
        assertEquals(0.05, SoftFade.radiusOf(0.001), 1e-9)
        assertEquals(4.0, SoftFade.radiusOf(100.0), 1e-9)
    }
}