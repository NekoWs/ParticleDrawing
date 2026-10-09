package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.FunctionVar
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.core.easing.EasingType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 循环回卷：loop 动画回卷到开头时恢复循环起点快照，函数对象运行时的 rand/global 保持确定性。
 */
class LoopWrapRestoreTest {

    private fun animation(): ParticleAnimation {
        val fx = FunctionObject(
            id = "fx0",
            name = "fx0",
            center = doubleArrayOf(0.0, 0.0, 0.0),
            source = "let r = 0\nfunc setup() { r = rand(); this.spawn(); }\n" +
                "func process() { for (const p of this.particles) { p.position.x = r; } }",
            seed = 7,
            vars = mapOf("k" to FunctionVar(0.0, listOf(Keyframe(1000.0, 0.0, EasingType.LINEAR)))),
            duration = 0,
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
    fun loopWrapRestoresDeterministicRuntime() {
        val player = ClientAnimationPlayer(animation(), Vec3.ZERO, startGameTick = 0L, currentGameTick = 0L)
        val state = player.currentStates().first { it.id == "fx0:p0" }
        val first = state.pos.x

        // 推进到循环末尾前一 tick（maxMs=1000，即 950ms），随后回卷到 t=0。
        player.tick(19L)
        player.advanceFrame(950.0)
        player.tick(20L)
        player.advanceFrame(0.0)

        assertEquals(0, player.currentMsValue)
        // r 来自 setup 的 rand()，回卷后必须与首圈一致。
        assertEquals(first, player.currentStates().first { it.id == "fx0:p0" }.pos.x, 1e-12)
    }
}