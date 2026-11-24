package work.nekow.particledrawing

import work.nekow.particledrawing.api.ParticleTextures
import work.nekow.particledrawing.core.client.ParticleSpriteAnimation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParticleTextureAnimationTest {
    @Test fun `贴图引用生成与解析`() {
        assertEquals("particle:minecraft:end_rod", ParticleTextures.of("minecraft:end_rod"))
        assertEquals("particle:minecraft:end_rod#3", ParticleTextures.of("minecraft:end_rod", 3))
        assertEquals("particle:minecraft:end_rod@0.05", ParticleTextures.animated("minecraft:end_rod", 0.05))

        val plain = ParticleTextures.parse("particle:minecraft:end_rod")
        assertEquals("minecraft:end_rod", plain?.particle?.toString())
        assertNull(plain?.frame)
        assertNull(plain?.fps)

        val fps = ParticleTextures.parse("particle:minecraft:end_rod@0.05")
        assertEquals(0.05, fps?.fps)
        val frame = ParticleTextures.parse("particle:minecraft:end_rod#3")
        assertEquals(3, frame?.frame)
    }

    @Test fun `有限寿命按寿命选帧`() {
        val animation = ParticleSpriteAnimation(frameHeight = 16, frames = 8)
        assertEquals(0, animation.frameAt(ageTicks = 0.0, lifetimeTicks = 1200))
        assertEquals(3, animation.frameAt(ageTicks = 600.0, lifetimeTicks = 1200))
        assertEquals(7, animation.frameAt(ageTicks = 1200.0, lifetimeTicks = 1200))
    }

    @Test fun `无限寿命默认循环而显式帧率按时间选帧`() {
        val looping = ParticleSpriteAnimation(frameHeight = 16, frames = 8)
        assertEquals(0, looping.frameAt(ageTicks = 0.0, lifetimeTicks = -1))
        assertEquals(4, looping.frameAt(ageTicks = 30.0, lifetimeTicks = -1))

        // 显式帧率压到很低时，年龄把整段序列走完之前都停在第一帧，相当于固定初始大小。
        val slow = ParticleSpriteAnimation(frameHeight = 16, frames = 8, fps = 0.05)
        assertEquals(0, slow.frameAt(ageTicks = 0.0, lifetimeTicks = -1))
        assertEquals(0, slow.frameAt(ageTicks = 200.0, lifetimeTicks = -1))
        assertEquals(2, slow.frameAt(ageTicks = 1000.0, lifetimeTicks = -1))
    }
}
