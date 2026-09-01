package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimKeyframe
import work.nekow.particledrawing.animation.AnimParticle
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.easing.EasingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 客户端播放器按服务端权威进度（gameTime 时钟）定位帧的回归测试：
 * 同一 startGameTick + gameTime 的所有客户端必然得到同一帧。
 */
class ClientAnimationPlayerProgressTest {

    private fun animation(loop: Boolean): ParticleAnimation {
        val particle = AnimParticle(
            id = "p0", color = Color.WHITE, scale = floatArrayOf(1f, 1f, 1f),
            glowing = false, lightLevel = 0, pos = Vec3(0.0, 0.0, 0.0), vel = Vec3.ZERO,
        )
        val track = AnimTrack(
            pr = "pos.x", ids = listOf("p0"),
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
    fun seeksToProgressOnConstruction() {
        // startGameTick=1000，收到播放包时 gameTime=1005 → 应从第 5 帧开始
        val player = ClientAnimationPlayer(animation(true), Vec3(100.0, 0.0, 0.0), startGameTick = 1000L, currentGameTick = 1005L)
        assertEquals(5, player.currentTickValue)
        assertEquals(102.5, posOf(player), 1e-6) // origin 100 + pos.x 插值 2.5
    }

    @Test
    fun advancesWithGameClock() {
        val player = ClientAnimationPlayer(animation(true), Vec3(100.0, 0.0, 0.0), startGameTick = 1000L, currentGameTick = 1000L)
        assertTrue(player.tick(1001L))
        assertEquals(1, player.currentTickValue)
        assertTrue(player.tick(1006L))
        assertEquals(6, player.currentTickValue)
        assertEquals(103.0, posOf(player), 1e-6) // 6/20 * 10 = 3
    }

    @Test
    fun wrapsLoopAtTimelineEnd() {
        val player = ClientAnimationPlayer(animation(true), Vec3(100.0, 0.0, 0.0), startGameTick = 1000L, currentGameTick = 1019L)
        assertEquals(19, player.currentTickValue)
        assertFalse(player.consumeJustLooped())
        assertTrue(player.tick(1020L)) // elapsed=20 → 回卷到 0
        assertEquals(0, player.currentTickValue)
        assertTrue(player.consumeJustLooped())
        assertEquals(100.0, posOf(player), 1e-6)
        assertTrue(player.tick(1025L))
        assertEquals(5, player.currentTickValue)
    }

    @Test
    fun nonLoopingFinishesAtTimelineEnd() {
        val player = ClientAnimationPlayer(animation(false), Vec3(100.0, 0.0, 0.0), startGameTick = 1000L, currentGameTick = 1019L)
        assertEquals(19, player.currentTickValue)
        assertTrue(player.tick(1019L)) // 同 tick：目标不变
        assertFalse(player.tick(1020L)) // elapsed=20 → 结束
    }

    @Test
    fun lateJoinJumpsToCurrentProgress() {
        // 迟到加入：startGameTick=1000，但收到时已 1037 → 循环动画定位 37 % 20 = 17
        val player = ClientAnimationPlayer(animation(true), Vec3(100.0, 0.0, 0.0), startGameTick = 1000L, currentGameTick = 1037L)
        assertEquals(17, player.currentTickValue)
    }
}