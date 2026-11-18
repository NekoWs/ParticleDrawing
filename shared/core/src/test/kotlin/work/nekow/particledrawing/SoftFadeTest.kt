package work.nekow.particledrawing

import work.nekow.particledrawing.api.SoftFade
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoftFadeTest {
    @Test
    fun `沿地面距离平滑淡出并保留采样范围外的透明度`() {
        val world = TestSurface(10.0)
        fun alpha(y: Double) = SoftFade.alpha(world, Vec3(2.0, y, 3.0), 1.0)
        assertEquals(0f, alpha(9.8))
        assertTrue(alpha(10.1) in 0f..0.1f)
        assertEquals(0.5f, alpha(10.5))
        assertEquals(1f, alpha(11.5))
        assertEquals(1f, SoftFade.alpha(world, Vec3.ZERO, 0.0))
    }
}
