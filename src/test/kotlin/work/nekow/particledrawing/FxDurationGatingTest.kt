package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.FunctionVar
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.core.easing.EasingType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 派生粒子对象时长门控回归测试：函数对象整体时长结束后粒子应回收，
 * duration <= 0 视为无时长上限（兼容旧工程解析回退 0）。
 */
class FxDurationGatingTest {

    private fun animation(duration: Int): ParticleAnimation {
        val fx = FunctionObject(
            id = "fx0",
            name = "fx0",
            center = doubleArrayOf(0.0, 0.0, 0.0),
            count = 1,
            setup = "",
            process = "Context.position = [Context.index, 0, 0];",
            funcs = "",
            seed = 0,
            // 变量关键帧把 maxTick 撑到 100，避免动画在 duration 处提前结束，便于观测时长门控。
            vars = mapOf("k" to FunctionVar(0.0, listOf(Keyframe(100.0, 0.0, EasingType.LINEAR)))),
            duration = duration,
            step = 5,
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
    fun derivedParticlesHideAfterDuration() {
        // 初始定位到 t=5（< st+duration=10）：可见
        val player = ClientAnimationPlayer(animation(10), Vec3.ZERO, startGameTick = 1000L, currentGameTick = 1005L)
        val state = player.currentStates().first { it.id == "fx0:p0" }
        assertTrue(state.visible)

        // elapsed=15 → t=15，超过对象时长 10：回收
        player.tick(1015L)
        assertFalse(state.visible)

        // elapsed=105 → 循环回卷到 t=5：重新入场
        player.tick(1105L)
        assertTrue(state.visible)
    }

    @Test
    fun zeroDurationTreatsAsNoDurationLimit() {
        // duration=0：不因时长隐藏（仅 st 门控），兼容旧工程缺省 duration 0 的解析回退。
        val player = ClientAnimationPlayer(animation(0), Vec3.ZERO, startGameTick = 1000L, currentGameTick = 1015L)
        val state = player.currentStates().first { it.id == "fx0:p0" }
        assertTrue(state.visible)
    }
}