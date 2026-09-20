package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.ParticleHost
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.parseProgram
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跨仓一致性：**编辑器生成的程序在播放端跑出的点，必须与编辑器预览的逐点相同**。
 *
 * 为什么需要这个测试：编辑器自己有一份 JS 运行时（是播放端的镜像实现），它能证明
 * 「导出程序在我这份镜像里算出的点 = 预览」，但那**是自己证明自己**——两份实现一旦分岔
 * （取整、hash、点数分配、多摆放的中心表……任何一处），编辑器那边仍然全绿，只有到了游戏里
 * 才看得出来。真正的证据是**播放端**跑同一份程序算出同一批点。
 *
 * 夹具在 `src/test/resources/editor-fixture/`，由编辑器侧生成
 * （`particle-editor/test/editor-fixture.test.js`，那边同时检查夹具没过期）：
 * `index.txt` 是清单（classpath 目录不可枚举），每个夹具含 SOURCE 段（程序原文）与
 * POINTS 段（编辑器预览算出的坐标）。这里只读自己仓库的资源，**不依赖编辑器仓库路径**。
 *
 * 当前覆盖：表面采样 / 棱采样 / 顶点采样 / 体素填充 / 3 个同形物体合并成一份程序（多摆放）。
 */
class EditorProgramConformanceTest {

    private class TestHost(override val index: Int) : ParticleHost {
        override val pos = DoubleArray(3)
        override val color = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
        override val vel = DoubleArray(3)
        override val scale = DoubleArray(3) { 1.0 }
        override var scaleDim = 0
        override var glow = false
        override var light = 0.0
        override var life = -1.0
        override val rotation = DoubleArray(3)
        override var billboard = true
        override var spinLocal = true
        override val fields = HashMap<String, Any?>()
        override fun kill() { /* 本测试不关心 */ }
    }

    /** `steps`：阶段 / t(ms) / delta(ms)。行为要跑 tick/process 才看得出效果，两端按同一张表驱动。 */
    private data class Fixture(
        val source: String,
        val steps: List<Triple<String, Double, Double>>,
        val points: List<DoubleArray>,
    )

    private fun resourceText(pathInJar: String): String {
        val stream = javaClass.getResourceAsStream(pathInJar)
            ?: error("找不到夹具 $pathInJar —— 由编辑器侧 test/editor-fixture.test.js 生成")
        return stream.bufferedReader(Charsets.UTF_8).readText()
    }

    /** 已知缺口：夹具名 → 原因。跳过但打印，绝不静默。 */
    private fun knownGaps(): Map<String, String> {
        val stream = javaClass.getResourceAsStream("/editor-fixture/known-gaps.txt") ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8).readText().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val i = line.indexOf('|')
                if (i < 0) line.trim() to "" else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
    }

    private fun fixtureNames(): List<String> =
        resourceText("/editor-fixture/index.txt").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun loadFixture(name: String): Fixture {
        val text = resourceText("/editor-fixture/$name")
        val srcMark = "-----SOURCE-----"
        val stpMark = "-----STEPS-----"
        val ptsMark = "-----POINTS-----"
        val iSrc = text.indexOf(srcMark)
        val iStp = text.indexOf(stpMark)
        val iPts = text.indexOf(ptsMark)
        check(iSrc >= 0 && iPts > iSrc) { "$name 格式不对：缺少 $srcMark / $ptsMark" }
        // 有 STEPS 段时 SOURCE 到 STEPS 为止；没有就到 POINTS 为止
        val iSrcEnd = if (iStp in (iSrc + 1) until iPts) iStp else iPts
        val source = text.substring(iSrc + srcMark.length, iSrcEnd).trim('\r', '\n')
        val steps = if (iStp in (iSrc + 1) until iPts) {
            text.substring(iStp + stpMark.length, iPts).lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val p = line.split(' ').filter { it.isNotEmpty() }
                    Triple(p[0], p[1].toDouble(), if (p.size > 2) p[2].toDouble() else 0.0)
                }
        } else {
            listOf(Triple("setup", 0.0, 0.0))
        }
        val points = text.substring(iPts + ptsMark.length).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val p = line.split(' ').filter { it.isNotEmpty() }
                DoubleArray(3) { p[it].toDouble() }
            }
        return Fixture(source, steps, points)
    }

    /** 按时间表跑，取回每个 spawn 出来的粒子位置 */
    private fun runPositions(source: String, steps: List<Triple<String, Double, Double>>): List<DoubleArray> {
        val program = parseProgram(source)
        val obj = ScriptRuntime.createObjectState(0)
        val particles = ArrayList<ParticleHost>()
        var serial = 0
        val spawn: () -> ParticleHost = {
            val h = TestHost(serial++)
            particles.add(h)
            h
        }
        for ((phase, tms, dms) in steps) {
            val ctx = ScriptRuntime.ScriptCtx(tms, 100.0, emptyMap(), particles, spawn, dms, false, {})
            when (phase) {
                "setup" -> ScriptRuntime.runSpawnSetup(program, obj, ctx)
                "tick" -> ScriptRuntime.runTickFrame(program, obj, ctx)
                "process" -> ScriptRuntime.runProcessFrame(program, obj, ctx)
                else -> error("未知阶段: $phase")
            }
        }
        return particles.map { h -> DoubleArray(3) { i -> h.pos[i] } }
    }

    @Test
    fun `清单里的每个夹具都能在播放端逐点复现编辑器预览`() {
        val names = fixtureNames()
        assertTrue(names.isNotEmpty(), "夹具清单是空的？")

        val report = StringBuilder()
        val problems = ArrayList<String>()
        val gaps = knownGaps()
        for (name in names) {
            val gap = gaps[name]
            if (gap != null) {
                println("SKIP $name —— 已知缺口：$gap")
                continue
            }
            val fx = loadFixture(name)
            if (fx.points.isEmpty()) { problems.add("$name 里没有期望坐标"); continue }

            val got = runPositions(fx.source, fx.steps)
            if (fx.points.size != got.size) {
                // 点数都不同就没必要逐点比了，直接记下来继续跑下一个夹具——
                // **一次跑完就知道所有分歧**，比在第一个夹具上抛异常有用得多。
                problems.add("$name 点数不一致：编辑器 ${fx.points.size}，播放端 ${got.size}")
                continue
            }

            var maxErr = 0.0
            var worst = -1
            for (i in fx.points.indices) {
                for (c in 0..2) {
                    val e = abs(fx.points[i][c] - got[i][c])
                    if (e > maxErr) { maxErr = e; worst = i }
                }
            }
            // 容差 1e-9：坐标是量化表（×64 的整数）算回来的，两条实现应当**精确**相同；
            // 留一点余量只为浮点结合顺序，不是用来掩盖真实偏差。
            if (maxErr > 1e-9) {
                problems.add(
                    "$name 第 $worst 点偏差 $maxErr 超过 1e-9：编辑器 ${fx.points.getOrNull(worst)?.toList()}，" +
                        "播放端 ${got.getOrNull(worst)?.toList()}",
                )
            }
            report.append("$name=${fx.points.size}点 ")
        }
        assertTrue(problems.isEmpty(), "跨仓逐点比对失败：\n" + problems.joinToString("\n"))
        println("跨仓逐点比对通过：$report")
    }
}
