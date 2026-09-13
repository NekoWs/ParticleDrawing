package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimKeyframe
import work.nekow.particledrawing.animation.AnimParticle
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.TextChar
import work.nekow.particledrawing.animation.TextObject
import work.nekow.particledrawing.animation.TrackPr
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.easing.EasingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * v17 文字对象去组后的轨道属主（"t:<id>"）求值回归：
 * op 增量 / 成员解析 / 广告牌与自转状态传播。
 */
class TextOwnerTrackTest {

    private fun textAnim(loop: Boolean): ParticleAnimation {
        val p0 = AnimParticle(
            "p0", Color.WHITE, floatArrayOf(1f, 1f, 1f), false, 0,
            Vec3(0.0, 0.0, 0.0), Vec3.ZERO,
            billboard = false, spinLocal = true,
        )
        val p1 = AnimParticle(
            "p1", Color.WHITE, floatArrayOf(1f, 1f, 1f), false, 0,
            Vec3(2.0, 0.0, 0.0), Vec3.ZERO,
        )
        val tx = TextObject(
            id = "txt1", name = "t", text = "AB", font = "x", fontSize = 24, weight = "normal",
            italic = false, color = Color.WHITE, strokeColor = null, strokeWidth = 0,
            align = "left", lineHeight = 1.0, letterSpacing = 0.0, st = 0, life = -1,
            chars = listOf(
                TextChar(0, 65, Vec3(0.0, 0.0, 0.0), doubleArrayOf(0.2, 0.2), listOf("p0")),
                TextChar(1, 66, Vec3(0.4, 0.0, 0.0), doubleArrayOf(0.2, 0.2), listOf("p1")),
            ),
        )
        val track = AnimTrack(
            pr = TrackPr.POS_X, ids = listOf("t:txt1"),
            keyframes = listOf(
                AnimKeyframe(0, 0.0, EasingType.LINEAR),
                AnimKeyframe(1000, 5.0, EasingType.LINEAR),
            ),
            mode = AnimTrack.Mode.OP,
        )
        return ParticleAnimation(loop, listOf(p0, p1), listOf(track), emptyMap(), texts = listOf(tx))
    }

    private fun xOf(player: ClientAnimationPlayer, id: String): Double =
        player.currentStates().first { it.id == id }.pos.x

    @Test
    fun textOwnerOpDeltaMovesMembers() {
        val player = ClientAnimationPlayer(textAnim(false), Vec3.ZERO)
        assertEquals(0.0, xOf(player, "p0"), 1e-9)
        player.tick(1L)   // 50ms
        player.tick(19L)  // 950ms → op 增量 4.75
        assertEquals(4.75, xOf(player, "p0"), 1e-6)
        assertEquals(6.75, xOf(player, "p1"), 1e-6)
    }

    @Test
    fun particleBillboardAndSpinStatePropagate() {
        val player = ClientAnimationPlayer(textAnim(false), Vec3.ZERO)
        val s = player.currentStates().first { it.id == "p0" }
        assertFalse(s.billboard)
        // 无粒子级 spin 轨道 → 自转恒 0
        assertEquals(0.0, s.spin[0], 1e-9)
    }
}