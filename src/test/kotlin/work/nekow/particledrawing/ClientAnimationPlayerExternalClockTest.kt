package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimKeyframe
import work.nekow.particledrawing.animation.AnimParticle
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.TrackPr
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.easing.EasingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 外部播放时钟（tickExternal）驱动的播放器：任意 seek、循环回卷与非循环结束。
 */
class ClientAnimationPlayerExternalClockTest {

    private fun animation(loop: Boolean): ParticleAnimation {
        val particle = AnimParticle(
            id = "p0", color = Color.WHITE, scale = floatArrayOf(1f, 1f, 1f),
            glowing = false, lightLevel = 0, pos = Vec3(0.0, 0.0, 0.0), vel = Vec3.ZERO,
        )
        val track = AnimTrack(
            pr = TrackPr.POS_X, ids = listOf("p0"),
            keyframes = listOf(
                AnimKeyframe(0, 0.0, EasingType.LINEAR),
                AnimKeyframe(20, 10.0, EasingType.LINEAR),
            ),
            mode = AnimTrack.Mode.SET,
        )
        return ParticleAnimation(loop, listOf(particle), listOf(track), emptyMap())
    }

    private fun posOf(player: ClientAnimationPlayer): Double =
        player.currentStates().first { it.id == "p0" }.pos.x

    @Test
    fun startsAtExplicitTick() {
        val player = ClientAnimationPlayer(animation(true), Vec3.ZERO, 0L, 0L, initialTick = 5)
        assertEquals(5, player.currentTickValue)
        assertEquals(2.5, posOf(player), 1e-6)
    }

    @Test
    fun advancesAndSeeksBackward() {
        val player = ClientAnimationPlayer(animation(true), Vec3.ZERO, 0L, 0L, initialTick = 0)
        assertTrue(player.tickExternal(6))
        assertEquals(6, player.currentTickValue)
        assertEquals(3.0, posOf(player), 1e-6)
        // 向后 seek（非循环语义下由外部时钟决定是否回卷；这里 loop 动画按回卷处理）
        assertTrue(player.tickExternal(2))
        assertEquals(2, player.currentTickValue)
        assertEquals(1.0, posOf(player), 1e-6)
    }

    @Test
    fun loopsAtTimelineEnd() {
        val player = ClientAnimationPlayer(animation(true), Vec3.ZERO, 0L, 0L, initialTick = 19)
        assertEquals(19, player.currentTickValue)
        assertFalse(player.consumeJustLooped())
        assertTrue(player.tickExternal(20)) // 回卷到 0
        assertEquals(0, player.currentTickValue)
        assertTrue(player.consumeJustLooped())
    }

    @Test
    fun nonLoopingFinishesPastEnd() {
        val player = ClientAnimationPlayer(animation(false), Vec3.ZERO, 0L, 0L, initialTick = 19)
        assertEquals(19, player.currentTickValue)
        assertTrue(player.tickExternal(19)) // 同 tick：目标不变，仍在播放
        assertFalse(player.tickExternal(20)) // 越过 maxTick → 结束
    }
}