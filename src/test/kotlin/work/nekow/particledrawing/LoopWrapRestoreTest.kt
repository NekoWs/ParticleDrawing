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
 * 循环回卷回归：loop 动画回卷到开头时，函数对象运行时不应漂移——
 * 恢复循环起点快照（而非带脏 objState 重新 setup），保证 rand/global 的确定性。
 */
class LoopWrapRestoreTest {

    private fun animation(): ParticleAnimation {
        val fx = FunctionObject(
            id = "fx0",
            name = "fx0",
            center = doubleArrayOf(0.0, 0.0, 0.0),
            source = "func setup() { global r = rand(); this.spawn(); }\n" +
                "func process(delta) { for (const p of this.particles) { p.position.x = r; } }",
            seed = 7,
            vars = mapOf("k" to FunctionVar(0.0, listOf(Keyframe(20.0, 0.0, EasingType.LINEAR)))),
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

        // 推进到循环末尾前一 tick（maxTick=20），随后回卷到 t=0。
        player.tick(19L)
        player.tick(20L)

        assertEquals(0, player.currentTickValue)
        // r 来自 setup 的 rand()；回卷后必须与首圈一致（确定性恢复，而非脏 PRNG 继续推进）。
        assertEquals(first, player.currentStates().first { it.id == "fx0:p0" }.pos.x, 1e-12)
    }
}