package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.FunctionVar
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.ProcessClock
import work.nekow.particledrawing.core.client.ClientAnimationManager
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 派生粒子的 tick 间插值必须为 0（症状为重影）。
 *
 * 派生粒子的位置由脚本 process 逐渲染帧整体重写，一个 game tick 内已经换了整整一幅图形；
 * 若只按 game tick 写进桥接粒子、再由原版按 partialTick 在上一 tick 与本 tick 之间插值，
 * 画出来就是相邻两幅迹线的混合。这里量症状（每段每 tick 的位移与弦中点偏离真实曲线的量），
 * 并验证桥接端点由生产代码的 [ClientAnimationManager.snapDirectWrite] 决定：
 * 派生粒子取 true（`xo=x`，插值量恒 0），普通粒子取 false（保留插值做帧率平滑）。
 *
 * 无游戏进程时构造不出 `BridgeParticle`（构造要 Minecraft 的粒子图集与 ClientLevel），
 * 所以插值量为 0 这一条落在判定函数与端点语义上。
 */
class DerivedParticleInterpolationTest {

    /** 一个 game tick 边界上渲染端读到的状态：位置来自上一个渲染帧的 process 时刻 [frameTime]。 */
    private class Snap(val frameTime: Double, val pos: List<Vec3>)

    private fun animation(frameSync: Boolean = false): ParticleAnimation = ParticleAnimation(
        loop = false,
        particles = emptyList(),
        tracks = emptyList(),
        groups = emptyMap(),
        functions = listOf(
            // duration=300000：时间轴非空（与成品工程的 maxMs=356308 同一量级，非空才走 msAt 的封顶口径）
            FunctionObject("fx", "fx", doubleArrayOf(0.0, 0.0, 0.0), TRACE_SOURCE, 0, emptyMap(), 300_000, frameSync = frameSync),
        ),
    )

    /** 按原版帧表跑 [frames] 帧真实播放器，返回每个 game tick 边界上读到的派生粒子状态。 */
    private fun tickSnapshots(player: ClientAnimationPlayer, frames: Int): List<Snap> {
        val snaps = ArrayList<Snap>()
        val clock = ProcessClock()
        var gameTick = 0L
        var frameTime = 0.0
        for (step in frameSchedule(60.0, frames, player.maxMsValue)) {
            repeat(step.ticks) {
                gameTick++
                player.tick(gameTick)
                // 这一刻正是渲染端 sync() 读到的状态：来自上一个渲染帧的 process 结果
                snaps.add(Snap(frameTime, derivedPositions(player)))
            }
            // 与生产路径同一套：渲染帧时刻由单调游标给出（frameTick 用的就是它）
            frameTime = clock.next(step.baseMs, step.partialTick, 50.0, player.maxMsValue)
            player.advanceFrame(frameTime)
        }
        return snaps
    }

    private fun derivedPositions(player: ClientAnimationPlayer): List<Vec3> = player.currentStates()
        .filter { it.id.startsWith("fx:p") }
        .sortedBy { it.id.removePrefix("fx:p").toInt() }
        .map { it.pos }

    private fun chord(a: Vec3, b: Vec3): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `症状：每段每 tick 的位移非零（snap=false 时就是插值量）`() {
        val snaps = tickSnapshots(ClientAnimationPlayer(animation(), Vec3.ZERO, 0L, 0L), 400)
        assertTrue(snaps.size > 100, "帧表应当跑出足够多的 tick 快照，实际 ${snaps.size}")

        var maxChord = 0.0
        var sumChord = 0.0
        var n = 0
        for (k in 1 until snaps.size) {
            val a = snaps[k - 1].pos
            val b = snaps[k].pos
            for (i in a.indices) {
                val c = chord(a[i], b[i])
                maxChord = max(maxChord, c)
                sumChord += c
                n++
            }
        }
        val meanChord = sumChord / n
        // 图形半径 1.2×0.9 世界单位：位移均值到图上量级 = 被插值的是两条完全不同的迹线
        assertTrue(meanChord > 0.5, "派生粒子每 tick 位移应达图形量级，均值 $meanChord")
        assertTrue(maxChord > 1.0, "最坏一格位移应超过 1 个世界单位，最大 $maxChord")
    }

    @Test
    fun `弦中点偏离真实曲线的量（重影位移）非零`() {
        val snaps = tickSnapshots(ClientAnimationPlayer(animation(), Vec3.ZERO, 0L, 0L), 400)
        // 与脚本同一公式的解析曲线：position = (sin(a)*1.2, cos(a*1.3)*0.9, 0)，a = time*0.02 + i*0.15
        var maxMid = 0.0
        for (k in 1 until snaps.size) {
            val a = snaps[k - 1]
            val b = snaps[k]
            if (b.frameTime <= a.frameTime) continue
            val midT = (a.frameTime + b.frameTime) * 0.5
            for (i in a.pos.indices) {
                // partialTick=0.5 时原版画在弦中点，真实曲线在两端时刻的中点处：两者之差就是重影偏移
                val midX = (a.pos[i].x + b.pos[i].x) * 0.5
                val midY = (a.pos[i].y + b.pos[i].y) * 0.5
                val angle = midT * 0.02 + i * 0.15
                val dx = midX - sin(angle) * 1.2
                val dy = midY - cos(angle * 1.3) * 0.9
                maxMid = max(maxMid, sqrt(dx * dx + dy * dy))
            }
        }
        assertTrue(maxMid > 0.05, "弦中点应明显偏离真实曲线（重影可见），最大偏移 $maxMid 世界单位")
    }

    @Test
    fun `派生粒子直写关闭 tick 间插值，插值量为 0`() {
        val snaps = tickSnapshots(ClientAnimationPlayer(animation(), Vec3.ZERO, 0L, 0L), 400)
        val a = snaps[snaps.size - 2].pos
        val b = snaps.last().pos
        // 桥接端点：xo 是上一 tick 位置，x 是本 tick 位置；snap 由生产代码判定
        val snap = ClientAnimationManager.snapDirectWrite(true)
        assertTrue(snap, "派生粒子必须关闭 tick 间插值")
        for (i in a.indices) {
            var xo = a[i]
            val x = b[i]
            if (snap) xo = x
            assertEquals(0.0, chord(xo, x), 0.0, "派生粒子的插值量必须为 0（xo 与 x 同值）")
        }
        // 对照：同一批位置若按普通粒子的策略写，插值量就是整段 50ms 位移
        assertFalse(ClientAnimationManager.snapDirectWrite(false), "普通粒子必须保留 tick 间插值")
        assertTrue(chord(a[0], b[0]) > 0.0, "普通粒子的插值量不为 0（保留帧率平滑）")
    }

    @Test
    fun `帧级同步派生粒子走另一条路径，本来就不插值`() {
        assertTrue(
            ClientAnimationPlayer(animation(frameSync = true), Vec3.ZERO, 0L, 0L).isFrameSyncDerived("fx:p0"),
            "frameSync=true 的派生粒子由 syncDerivedFrame 按帧直写",
        )
        assertFalse(ClientAnimationPlayer(animation(), Vec3.ZERO, 0L, 0L).isFrameSyncDerived("fx:p0"))
    }

    @Test
    fun `示波器工程那支迹线脚本走的就是这条 tick 路径`() {
        val source = javaClass.getResourceAsStream("/editor-fixture/oscilloscope-fx1.txt")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(source != null, "缺少夹具 editor-fixture/oscilloscope-fx1.txt")
        // 工程文件 case 里 fx1 没勾帧级同步、duration=0，音频 durMs=356308 提供非空时间轴；
        // 面板变量取自同一份工程夹具（缺一个脚本就编译不出来）
        val vars = FxOscilloscopeScriptTest.FX1_VARS.mapValues { FunctionVar(it.value, emptyList()) }
        val fx = FunctionObject("fx1", "X-Y 光束（主迹线）", doubleArrayOf(0.0, 0.0, 0.0), source, 1, vars, 0)
        val player = ClientAnimationPlayer(
            ParticleAnimation(false, emptyList(), emptyList(), emptyMap(), functions = listOf(fx)),
            Vec3.ZERO, 0L, 0L,
        )
        assertFalse(player.isFrameSyncDerived("fx1:p0"), "该工程没开帧级同步，派生粒子按 game tick 同步")
        assertFalse(player.isStatic(), "带 process 的脚本必须每渲染帧推进（isStatic 会整块跳过）")
        assertTrue(player.currentStates().isNotEmpty(), "setup 应当铺出环形缓冲的粒子")
        assertTrue(ClientAnimationManager.snapDirectWrite(true), "这条路径必须关插值")
    }

    private companion object {
        const val SEGS = 40

        /** 每帧把整条迹线整体重写（与示波器 fx1 同一形状：环形缓冲槽位逐帧写新几何）。 */
        val TRACE_SOURCE = """
            func setup() {
              let i = 0
              while (i < $SEGS) {
                let p = this.spawn()
                p.billboard = false
                i = i + 1
              }
            }
            func process() {
              let t = this.time
              let i = 0
              while (i < this.particles.size()) {
                let p = this.particles[i]
                let a = t * 0.02 + i * 0.15
                p.position = vec3(sin(a) * 1.2, cos(a * 1.3) * 0.9, 0)
                p.rotation = vec3(0, 0, atan2(cos(a * 1.3), sin(a)) * RAD2DEG)
                p.scale = vec2(0.12, 0.02)
                i = i + 1
              }
            }
        """.trimIndent()
    }
}
