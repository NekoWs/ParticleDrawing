package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimParticle
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.api.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 函数对象级视图变换（脚本 this.viewScale / this.viewOffset）的应用层契约。
 *
 * 语义：`位置 = 位置 × viewScale + viewOffset`、`尺寸 = 尺寸 × viewScale`（均匀，不单独处理某一轴），
 * 只作用到**该函数对象自己的派生粒子**；未设置时（默认 1 / 0）与改动前完全一致。
 * 脚本会写 `width = lineW / viewScale` 来补偿线宽，所以尺寸必须真的被乘到。
 *
 * 这里测到的是播放端的粒子状态层（位置与三分量尺寸），顶点写入要活的 Minecraft，测不到。
 */
class FxViewTransformTest {

    private fun fx(id: String, source: String): FunctionObject =
        FunctionObject(id = id, name = id, center = doubleArrayOf(0.0, 0.0, 0.0), source = source, seed = 0, vars = emptyMap(), duration = 0)

    /** 一个设了视图变换的对象 + 一个没设的同类对象 + 一颗普通粒子，用来看「谁受影响」。 */
    private fun animation(viewSource: String): ParticleAnimation = ParticleAnimation(
        loop = false,
        particles = listOf(
            AnimParticle(
                "p0", Color.WHITE, floatArrayOf(5f, 5f, 1f), false, 0, Vec3(7.0, 0.0, 0.0), Vec3.ZERO, life = -1,
            ),
        ),
        tracks = emptyList(),
        groups = emptyMap(),
        functions = listOf(
            fx("view", viewSource),
            fx("plain", "func setup() { let p = this.spawn(); p.position = [1, 2, 0]; p.scale = 5; }"),
        ),
    )

    private fun player(viewSource: String): ClientAnimationPlayer =
        ClientAnimationPlayer(animation(viewSource), Vec3.ZERO, startGameTick = 0L, currentGameTick = 0L)

    private val plainSource = "func setup() { let p = this.spawn(); p.position = [1, 2, 0]; p.scale = 5; }"

    @Test
    fun `设置了视图变换时派生粒子的位置与尺寸一起缩放`() {
        val s = player("func setup() { this.viewScale = 2; this.viewOffset = vec2(3, 4); let p = this.spawn(); p.position = [1, 2, 0]; p.scale = 5; }")
            .currentStates().first { it.id == "view:p0" }
        // 位置 = (1,2,0) × 2 + (3,4,0) = (5,8,0)
        assertEquals(5.0, s.pos.x, 1e-9)
        assertEquals(8.0, s.pos.y, 1e-9)
        assertEquals(0.0, s.pos.z, 1e-9)
        // 尺寸 = [5,5,1] × 2（三分量均匀乘，线宽补偿靠脚本写 width = lineW / viewScale）
        assertEquals(listOf(10f, 10f, 2f), s.scale.toList())
    }

    @Test
    fun `process 里逐帧写视图变换同样生效`() {
        val s = player(
            "func setup() { let p = this.spawn(); p.position = [1, 2, 0]; p.scale = 4; }\n" +
                "func process() { this.viewScale = 3; }",
        ).currentStates().first { it.id == "view:p0" }
        assertEquals(3.0, s.pos.x, 1e-9)
        assertEquals(6.0, s.pos.y, 1e-9)
        assertEquals(listOf(12f, 12f, 3f), s.scale.toList())
    }

    @Test
    fun `viewOffset 用 vec3 时 z 也加偏移`() {
        val s = player("func setup() { this.viewOffset = vec3(1, 2, 3); let p = this.spawn(); p.position = [2, 2, 2]; p.scale = 1; }")
            .currentStates().first { it.id == "view:p0" }
        assertEquals(listOf(3.0, 4.0, 5.0), listOf(s.pos.x, s.pos.y, s.pos.z))
    }

    @Test
    fun `未设置视图变换时行为与以前一致`() {
        val s = player(plainSource).currentStates().first { it.id == "plain:p0" }
        assertEquals(listOf(1.0, 2.0, 0.0), listOf(s.pos.x, s.pos.y, s.pos.z))
        assertEquals(listOf(5f, 5f, 1f), s.scale.toList())
    }

    @Test
    fun `其它对象与普通粒子不受视图变换影响`() {
        val states = player("func setup() { this.viewScale = 2; this.viewOffset = vec2(3, 4); let p = this.spawn(); p.position = [1, 2, 0]; p.scale = 5; }")
            .currentStates()
        // 同动画里另一颗派生粒子（没设变换的对象）
        val plain = states.first { it.id == "plain:p0" }
        assertEquals(listOf(1.0, 2.0, 0.0), listOf(plain.pos.x, plain.pos.y, plain.pos.z))
        assertEquals(listOf(5f, 5f, 1f), plain.scale.toList())
        // 普通粒子：位置与尺寸都停在作者写下的值
        val ordinary = states.first { it.id == "p0" }
        assertEquals(listOf(7.0, 0.0, 0.0), listOf(ordinary.pos.x, ordinary.pos.y, ordinary.pos.z))
        assertEquals(listOf(5f, 5f, 1f), ordinary.scale.toList())
    }
}
