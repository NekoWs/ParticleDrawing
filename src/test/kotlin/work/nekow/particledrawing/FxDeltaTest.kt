package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.ParticleAnimation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 脚本 `this.delta` 的调度语义（编辑器 generators.js 的 runtime.lastPhaseMs 同一套）：
 * 距上一次真正跑过 process/tick 的毫秒数，上限 100ms，首次执行前是 0。
 * 生成程序里的速度积分/阻尼/追帧步数全靠它，所以「第一次给什么、间隔怎么算、封顶多少」都要钉住。
 *
 * 读数用粒子位置：每帧 spawn 一颗，x = 本次 delta、y = 累计 delta（把整条序列都留在场景里，可逐帧核）。
 */
class FxDeltaTest {

    private val source =
        "let sum = 0\n" +
            "func process() {\n" +
            "  sum = sum + this.delta\n" +
            "  let p = this.spawn()\n" +
            "  p.position = [this.delta, sum, 0]\n" +
            "}"

    private fun player(): ClientAnimationPlayer = ClientAnimationPlayer(
        ParticleAnimation(
            loop = false,
            particles = emptyList(),
            tracks = emptyList(),
            groups = emptyMap(),
            functions = listOf(
                // duration 给一个非 0 值：duration=0 且时间轴为空的动画被当成静态动画，
                // 静态动画只跑一次 setup、根本不跑 process（this.delta 也就无从谈起）。
                FunctionObject(id = "fx", name = "fx", center = doubleArrayOf(0.0, 0.0, 0.0), source = source, seed = 0, vars = emptyMap(), duration = 60_000),
            ),
        ),
        Vec3.ZERO,
        startGameTick = 0L,
        currentGameTick = 0L,
    )

    /** 每一帧那颗粒子的 (delta, 累计 delta)，按 spawn 顺序（= 帧顺序）。 */
    private fun rows(player: ClientAnimationPlayer): List<Pair<Double, Double>> =
        player.currentStates()
            .filter { it.id.startsWith("fx:p") }
            .sortedBy { it.id.removePrefix("fx:p").toInt() }
            .map { it.pos.x to it.pos.y }

    private fun assertRows(player: ClientAnimationPlayer, expected: List<Pair<Double, Double>>, tol: Double = 1e-9) {
        val rows = rows(player)
        assertEquals(expected.size, rows.size, "帧数对不上")
        expected.forEachIndexed { i, (d, sum) ->
            assertEquals(d, rows[i].first, tol, "第 $i 帧 delta")
            assertEquals(sum, rows[i].second, tol, "第 $i 帧累计 delta")
        }
    }

    @Test
    fun `首帧 delta 为 0，之后按帧间隔累加`() {
        // 构造时就已经推进过一次 process（t=0，与编辑器「t=0 就有一帧」一致），那一帧的 delta 记 0。
        val player = player()
        player.advanceFrame(0.0)
        assertRows(player, listOf(0.0 to 0.0, 0.0 to 0.0))
        player.advanceFrame(16.7)
        assertRows(player, listOf(0.0 to 0.0, 0.0 to 0.0, 16.7 to 16.7))
        player.advanceFrame(33.4)
        assertRows(player, listOf(0.0 to 0.0, 0.0 to 0.0, 16.7 to 16.7, 16.7 to 33.4))
        // 跨到下一个 50ms 桶：本次时刻 50.0，上一次阶段是 33.4，间隔 16.6
        player.advanceFrame(50.0)
        assertRows(player, listOf(0.0 to 0.0, 0.0 to 0.0, 16.7 to 16.7, 16.7 to 33.4, 16.6 to 50.0), 1e-9)
    }

    @Test
    fun `单帧间隔超过 100ms 时按 100ms 封顶`() {
        val player = player()
        player.advanceFrame(0.0)
        player.advanceFrame(500.0)
        assertRows(player, listOf(0.0 to 0.0, 0.0 to 0.0, 100.0 to 100.0))
    }

    @Test
    fun `tick 边界上的 process 与 50ms 网格对齐`() {
        // 到 t=50 时先补跑一次 50ms 边界的 tick（阶段时刻被挪到 50），紧接着 process 的 delta 记 50。
        val player = player()
        player.advanceFrame(0.0)
        player.advanceFrame(50.0)
        assertRows(player, listOf(0.0 to 0.0, 0.0 to 0.0, 50.0 to 50.0))
    }
}
