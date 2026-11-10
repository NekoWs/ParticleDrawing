package work.nekow.particledrawing

import work.nekow.particledrawing.animation.*
import work.nekow.particledrawing.math.Vec3
import kotlin.test.*

class FunctionTextureTest {
    private fun player(uv: UvData): ClientAnimationPlayer {
        val fx = FunctionObject("panel", "panel", doubleArrayOf(0.0, 0.0, 0.0),
            "func setup() { this.spawn({position:[0,0,0],scale:12,glow:true,life:-1}) }",
            0, emptyMap(), 60000, uv)
        return ClientAnimationPlayer(ParticleAnimation(false, emptyList(), emptyList(), emptyMap(),
            functions = listOf(fx)), Vec3.ZERO)
    }

    @Test fun `派生粒子出生即继承函数对象贴图与尺寸`() {
        val uv = UvData("orientation", UvData.Mode.ANIMATED, intArrayOf(32, 64),
            intArrayOf(0, 0), intArrayOf(32, 32), intArrayOf(32, 32), 2f, 2, true)
        val player = player(uv)
        val state = player.currentStates().single()
        assertSame(uv, state.uv)
        assertEquals(12f, state.scale[0])
        player.advanceFrame(500.0)
        assertSame(uv, player.currentStates().single().uv)
    }

    @Test fun `函数对象UV公式在出生和后续帧使用当前播放时间`() {
        val uv = UvData("orientation", UvData.Mode.STATIC, intArrayOf(64, 32),
            intArrayOf(0, 0), intArrayOf(16, 16), intArrayOf(16, 16), 2f, 2, true,
            uvStartExpr = arrayOf("floor(this.time/500)*16", null))
        val player = player(uv)
        assertContentEquals(intArrayOf(0, 0), player.currentStates().single().uv!!.uvStart)
        player.advanceFrame(500.0)
        assertContentEquals(intArrayOf(16, 0), player.currentStates().single().uv!!.uvStart)
        player.advanceFrame(1000.0)
        assertContentEquals(intArrayOf(32, 0), player.currentStates().single().uv!!.uvStart)
    }
}
