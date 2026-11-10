package work.nekow.particledrawing

import work.nekow.particledrawing.core.client.ParticleSpriteAnimation
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.api.Color
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ParticleSpriteAnimationTest {
    @Test
    fun `有限寿命按出生到消亡的进度依次换帧并停在末帧`() {
        val animation = ParticleSpriteAnimation(8, 8)
        assertEquals(0, animation.frameAt(0.0, 80))
        assertEquals(1, animation.frameAt(20.0, 80))
        assertEquals(3, animation.frameAt(40.0, 80))
        assertEquals(6, animation.frameAt(79.9, 80))
        assertEquals(7, animation.frameAt(100.0, 80))
        // 加倍寿命，同一张贴图也相应放慢。
        assertEquals(1, animation.frameAt(40.0, 160))
        for (age in 0..80) assertEquals(age * 7 / 80, animation.frameAt(age.toDouble(), 80))
    }

    @Test
    fun `无限寿命默认三秒循环且显式帧率使用经过时间`() {
        val natural = ParticleSpriteAnimation(8, 8)
        assertEquals(4, natural.frameAt(30.0, -1))
        assertEquals(0, natural.frameAt(60.0, -1))
        val timed = ParticleSpriteAnimation(8, 8, 8.0)
        assertEquals(4, timed.frameAt(10.0, 80))
        assertEquals(0, timed.frameAt(20.0, 80))
    }

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
