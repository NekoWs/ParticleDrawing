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
 * 时间轴单位为毫秒。
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
                AnimKeyframe(1000, 10.0, EasingType.LINEAR),
            ),
            mode = AnimTrack.Mode.SET,
        )
        return ParticleAnimation(loop, listOf(particle), listOf(track), emptyMap())
    }

    private fun posOf(player: ClientAnimationPlayer): Double =
        player.currentStates().first { it.id == "p0" }.pos.x

    @Test
    fun startsAtExplicitMs() {
        val player = ClientAnimationPlayer(animation(true), Vec3.ZERO, 0L, 0L, initialMs = 250)
        assertEquals(250, player.currentMsValue)
        assertEquals(2.5, posOf(player), 1e-6)
    }

    @Test
    fun advancesAndSeeksBackward() {
        val player = ClientAnimationPlayer(animation(true), Vec3.ZERO, 0L, 0L, initialMs = 0)
        assertTrue(player.tickExternal(300))
        assertEquals(300, player.currentMsValue)
        assertEquals(3.0, posOf(player), 1e-6)
        // 向后 seek（非循环语义下由外部时钟决定是否回卷；这里 loop 动画按回卷处理）
        assertTrue(player.tickExternal(100))
        assertEquals(100, player.currentMsValue)
        assertEquals(1.0, posOf(player), 1e-6)
    }

    @Test
    fun loopsAtTimelineEnd() {
        val player = ClientAnimationPlayer(animation(true), Vec3.ZERO, 0L, 0L, initialMs = 950)
        assertEquals(950, player.currentMsValue)
        assertFalse(player.consumeJustLooped())
        assertTrue(player.tickExternal(1000)) // 回卷到 0
        assertEquals(0, player.currentMsValue)
        assertTrue(player.consumeJustLooped())
    }

    @Test
    fun nonLoopingFinishesPastEnd() {
        val player = ClientAnimationPlayer(animation(false), Vec3.ZERO, 0L, 0L, initialMs = 950)
        assertEquals(950, player.currentMsValue)
        assertTrue(player.tickExternal(950)) // 同位置：目标不变，仍在播放
        assertFalse(player.tickExternal(1000)) // 越过 maxMs → 结束
    }
}