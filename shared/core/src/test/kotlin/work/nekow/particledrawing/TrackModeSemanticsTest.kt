package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AnimKeyframe
import work.nekow.particledrawing.animation.AnimParticle
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.TrackPr
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals

/** 轨道模式语义：SET 是绝对值、OP 是增量；同一分量上两类轨道要叠加，不能互相覆盖。 */
class TrackModeSemanticsTest {

    private fun particle(id: String) = AnimParticle(
        id, Color.WHITE, floatArrayOf(1f, 1f, 1f), false, 0, Vec3.ZERO, Vec3.ZERO,
    )

    private fun track(pr: TrackPr, ids: List<String>, mode: AnimTrack.Mode, vararg values: Double) =
        AnimTrack(
            pr, ids,
            values.mapIndexed { index, value ->
                AnimKeyframe(index * 1000, value, EasingType.LINEAR)
            },
            mode,
        )

    private fun animation(particles: List<AnimParticle>, tracks: List<AnimTrack>) =
        ParticleAnimation(false, particles, tracks, emptyMap(), emptyList())

    @Test
    fun `同一分量上的 SET 与 OP 叠加而不是互相覆盖`() {
        val anim = animation(
            listOf(particle("p0")),
            listOf(
                track(TrackPr.POS_X, listOf("p0"), AnimTrack.Mode.SET, 5.0, 5.0),
                track(TrackPr.POS_X, listOf("p0"), AnimTrack.Mode.OP, 2.0, 2.0),
            ),
        )
        val player = ClientAnimationPlayer(anim, Vec3.ZERO, 0L, 0L)
        player.tickExternal(500)
        val state = player.currentStates().first { it.id == "p0" }
        assertEquals(7.0, state.pos.x, 1e-6, "SET(5) + OP(2) 应为 7，OP 不能被当绝对值")
    }

    @Test
    fun `多目标 SET 轨道对每个目标都生效`() {
        val anim = animation(
            listOf(particle("p0"), particle("p1")),
            listOf(track(TrackPr.POS_X, listOf("p0", "p1"), AnimTrack.Mode.SET, 3.0, 3.0)),
        )
        val player = ClientAnimationPlayer(anim, Vec3.ZERO, 0L, 0L)
        player.tickExternal(500)
        val states = player.currentStates().associateBy { it.id }
        assertEquals(3.0, states.getValue("p0").pos.x, 1e-6)
        assertEquals(3.0, states.getValue("p1").pos.x, 1e-6)
    }

    @Test
    fun `粒子缩放的 OP 轨道按增量叠加`() {
        val anim = animation(
            listOf(particle("p0")),
            listOf(
                track(TrackPr.SCL_X, listOf("p0"), AnimTrack.Mode.SET, 1.0, 1.0),
                track(TrackPr.SCL_X, listOf("p0"), AnimTrack.Mode.OP, 0.5, 0.5),
            ),
        )
        val player = ClientAnimationPlayer(anim, Vec3.ZERO, 0L, 0L)
        player.tickExternal(500)
        val state = player.currentStates().first { it.id == "p0" }
        assertEquals(1.5f, state.scale[0], 1e-6f)
    }
}
