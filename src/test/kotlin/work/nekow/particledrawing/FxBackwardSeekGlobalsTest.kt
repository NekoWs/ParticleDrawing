package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.ParticleAnimation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 函数对象向后 seek 会重建 objState；顶层 let/const 必须重新注册，
 * 否则 setup 引用全局名会报 unknown variable。
 */
class FxBackwardSeekGlobalsTest {

    private fun animation(): ParticleAnimation {
        val fx = FunctionObject(
            id = "fx0",
            name = "fx0",
            center = doubleArrayOf(0.0, 0.0, 0.0),
            source = "const layer_count = 32, ring_points = 52\n" +
                "func setup() { this.spawn(); }\n" +
                "func process() { for (const p of this.particles) { p.position = [layer_count, 0, 0]; } }",
            seed = 0,
            vars = emptyMap(),
            duration = 8000,
        )
        return ParticleAnimation(
            loop = true,
            particles = emptyList(),
            tracks = emptyList(),
            groups = emptyMap(),
            functions = listOf(fx),
        )
    }

    @Test
    fun backwardSeekReRegistersTopLevelGlobals() {
        val player = ClientAnimationPlayer(animation(), Vec3.ZERO, 0L, 0L, initialMs = 0)
        player.advanceFrame(100.0)
        val forward = player.currentStates().first { it.id == "fx0:p0" }
        assertEquals(32.0, forward.pos.x, 1e-6)

        // 向后 seek：resetFxRuntime 重建 objState，setup 重新注册全局常量。
        player.advanceFrame(0.0)
        val back = player.currentStates().firstOrNull { it.id == "fx0:p0" }
        assertNotNull(back)
        assertEquals(32.0, back.pos.x, 1e-6)
    }
}