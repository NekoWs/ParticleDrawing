package work.nekow.particledrawing

import work.nekow.particledrawing.light.LightOcclusion
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.WorldBounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightOcclusionTest {
    @Test fun `逐点查找遮挡并保留朝向光源的墙面`() {
        val obstacle = WorldBounds(Vec3(1.0, -0.2, -0.2), Vec3(2.0, 0.25, 0.2))
        val distant = List(16) { i ->
            WorldBounds(Vec3(i.toDouble(), 3.0, 0.0), Vec3(i + 0.8, 4.0, 1.0))
        }
        val index = LightOcclusion.from(distant + obstacle)
        assertTrue(index.blocks(Vec3.ZERO, 3.0, 0.0, 0.0))
        assertFalse(index.blocks(Vec3.ZERO, 1.0, 0.0, 0.0))
        assertFalse(index.blocks(Vec3.ZERO, 3.0, 1.0, 0.0))
        val tangent = Vec3(0.0, 0.25, 0.0)
        assertFalse(index.blocks(tangent, 3.0, 0.25, 0.0))
        assertFalse(LightOcclusion.from(emptyList()).blocks(Vec3.ZERO, 3.0, 0.0, 0.0))
    }
}
