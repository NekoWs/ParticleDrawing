package work.nekow.particledrawing

import work.nekow.particledrawing.core.client.ParticleSpriteAnimation
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.api.Color
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ParticleSpritePlaybackTest {
    @Test
    fun `换帧使用PD年龄暂停不推进且显式寿命到点销毁`() {
        val particle = RenderParticle(UUID.randomUUID(), Vec3.ZERO, Color.WHITE, 1f, true, 0, 80)
        val animation = ParticleSpriteAnimation(8, 8)
        repeat(40) { particle.advanceEngine() }
        val beforePause = animation.frameAt(particle.ageTicks().toDouble(), particle.lifetimeTicks())
        assertEquals(beforePause, animation.frameAt(particle.ageTicks().toDouble(), particle.lifetimeTicks()))
        repeat(40) { particle.advanceEngine() }
        assertEquals(7, animation.frameAt(particle.ageTicks().toDouble(), particle.lifetimeTicks()))
        assertFalse(particle.isAlive())
    }
}
