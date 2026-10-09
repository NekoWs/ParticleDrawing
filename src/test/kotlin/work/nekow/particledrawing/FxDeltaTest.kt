package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.ParticleAnimation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 脚本 `this.delta` 的调度语义：距上一次跑过 process/tick 的毫秒数，上限 100ms，首次执行前是 0。
 *
 * 读数取自粒子位置：每帧 spawn 一颗，x = 本次 delta，y = 累计 delta。
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
                // duration=0（不限时长）+ 空时间轴：判据只看脚本有没有 process 阶段，
                // 这种动画照样每帧跑 process
                FunctionObject(id = "fx", name = "fx", center = doubleArrayOf(0.0, 0.0, 0.0), source = source, seed = 0, vars = emptyMap(), duration = 0),
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
        // 构造时已推进过一次 process（t=0 就有一帧，与编辑器一致），那一帧的 delta 记 0。
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
