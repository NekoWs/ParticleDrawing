package work.nekow.particledrawing

import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FxScriptLightTest {

    private fun player(source: String): ClientAnimationPlayer {
        val fx = FunctionObject("fx", "fx", doubleArrayOf(0.0, 0.0, 0.0), source, 0, emptyMap(), 0)
        return ClientAnimationPlayer(ParticleAnimation(false, emptyList(), emptyList(), emptyMap(), listOf(fx)), Vec3.ZERO, 0L, 0L)
    }

    @Test
    fun `移动调光转向与锥角都在渲染帧生效且清除光源仍能同步`() {
        val player = player(
            """
            func setup() { this.spawn({light: 15}) }
            func process() {
                let p = this.particles[0]
                let t = this.time / 100
                p.position.x = t * 2
                p.lightIntensity = 1 - t
                if (t >= 0.5) {
                    p.lightShape = []
                } else if (t > 0) {
                    let outer = 20 + 40 * t
                    p.lightShape = [12, outer, outer * 0.6, 1, t * 180, -55, "smooth"]
                }
            }
            """.trimIndent(),
        )
        val id = "fx:p0"
        assertFalse(player.isFrameSyncDerived(id))

        player.advanceFrame(25.0)
        val state = player.currentStates().single()
        assertTrue(player.isFrameSyncDerived(id))
        assertEquals(0.5, state.pos.x)
        assertEquals(0.75f, state.lightIntensity)
        val shape = assertNotNull(state.lightShape)
        assertEquals(30.0, shape.outerDeg)
        assertEquals(45.0, shape.yawDeg)

        player.advanceFrame(50.0)
        assertNull(player.currentStates().single().lightShape)
        assertTrue(player.isFrameSyncDerived(id))
    }

    @Test
    fun `静态脚本光源保留普通生成路径`() {
        val player = player("""func setup() { this.spawn({light: 15, lightShape: [12, 20, 10, 1, 0, -90, "smooth"]}); }""")
        assertTrue(player.isStatic())
        assertFalse(player.isFrameSyncDerived("fx:p0"))
    }
}
