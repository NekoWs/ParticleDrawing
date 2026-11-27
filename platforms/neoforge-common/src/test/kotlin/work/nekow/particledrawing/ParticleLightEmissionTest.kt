package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleSpawnSpec
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.server.ParticleData
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 全亮（glow）与向外投光（light）解耦后的契约：
 * 只写 glow 不该让粒子照亮世界；显式 lightLevel / light 才打开照明。
 */
class ParticleLightEmissionTest {

    @Test
    fun `glow 不再隐式投光`() {
        val visual = ParticleVisual().glowing(true)
        assertFalse(visual.emitsLight, "glow 只免光照，默认不该照亮世界")
        assertEquals(0, visual.lightLevel)
    }

    @Test
    fun `显式 lightLevel 打开照明`() {
        val visual = ParticleVisual().glowing(true).lightLevel(9)
        assertTrue(visual.emitsLight)
        assertEquals(9, visual.lightLevel)
    }

    @Test
    fun `运行时提亮补开外观照明位`() {
        val data = ParticleData.create(
            UUID.randomUUID(), Vec3.ZERO, Color.WHITE, 1f, -1, null,
            glowing = true, lightLevel = 0, offsetFromPivot = null,
            visual = ParticleVisual().glowing(true),
        )
        assertFalse(data.visual()!!.emitsLight)
        data.setLightLevel(9)
        assertTrue(data.visual()!!.emitsLight, "迟到玩家收到的出生包也要带照明位")
        assertEquals(9, data.lightLevel())
    }

    @Test
    fun `批量规格默认不投光并随显式等级打开`() {
        val spec = ParticleSpawnSpec()
        assertFalse(spec.resolvedVisual()?.emitsLight ?: false)
        spec.lightLevel(12)
        assertTrue(spec.resolvedVisual()!!.emitsLight)
    }
}
