package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.script.AudioValue
import work.nekow.particledrawing.animation.script.ParticleHost
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.ViewTransform
import work.nekow.particledrawing.animation.script.parseProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 实机夹具：示波器工程（`cases/示波器（示波器音乐冠军）.pdraw`）里 fx1「X-Y 光束（主迹线）」的脚本原文。
 *
 * 这支脚本在游戏里跑炸过一次——`this.delta is not available here (line 97, col 20)`，整支 fx 求值失败。
 * 所以把源码整个搬进来当回归夹具：它同时钉住「this.delta 不报错」和「这支脚本用到的成员/内建一个都不少」。
 * 夹具是工程文件里的原文，脚本改一行就要连着一起更新。
 */
class FxOscilloscopeScriptTest {

    private class Host(override val index: Int) : ParticleHost {
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
        override fun kill() {}
    }

    /** 示波器音频资产的最小替身：名字叫 Primer，列数够脚本取数即可（真实音频字节不进测试）。 */
    private fun primer(): AudioAsset = AudioAsset(
        id = "aud1", name = "Primer", fmt = 1, data = ByteArray(0), st = 0, durMs = 356_308, hopCount = 2,
        bpm = 1.0, beatOffsetMs = 0.0, onsetMax = 1.0, beats = listOf(0, 1000),
        rms = ShortArray(2), peak = ShortArray(2), centroid = ShortArray(2),
        onset = ByteArray(2), rolloff = ByteArray(2), bands = ByteArray(2 * 16),
    )

    @Test
    fun `示波器 fx1 整支脚本能解析并跑通 setup 与 process`() {
        val source = assertNotNull(
            javaClass.getResourceAsStream("/editor-fixture/oscilloscope-fx1.txt"),
            "缺少夹具 src/test/resources/editor-fixture/oscilloscope-fx1.txt",
        ).use { it.readBytes().toString(Charsets.UTF_8) }
        // 夹具就是那支报错的脚本：第 97 行必须是 this.delta 的用法，否则这条测试已经失真
        assertTrue(source.lines()[96].contains("this.delta"), "夹具第 97 行应当是 this.delta 的用法")

        val program = parseProgram(source)
        val obj = ScriptRuntime.createObjectState(1)
        val particles = ArrayList<ParticleHost>()
        var serial = 0
        val view = ViewTransform()
        val audio = primer()

        fun ctx(t: Double, delta: Double) = ScriptRuntime.ScriptCtx(
            t = t,
            duration = 0.0,
            vars = FX1_VARS,
            particles = particles,
            spawn = { Host(serial++).also { particles.add(it) } },
            deltaMs = delta,
            fastMath = false,
            print = {},
            st = 0.0,
            maxMs = 0.0,
            get = { name -> if (name == "Primer") AudioValue(audio, t, true, 1.0) else throw RuntimeException("unknown asset '$name'") },
            view = view,
        )

        ScriptRuntime.runSpawnSetup(program, obj, ctx(0.0, 0.0))
        // setup 按 segs = winMs/segMs ≈ 1152 铺满环形缓冲的粒子
        assertTrue(particles.size > 100, "setup 应当铺出环上的粒子，实际 ${particles.size}")

        // 连跑几帧：脚本第 97 行的 this.delta 就在这条路径上（修复前这里抛 "this.delta is not available here"）
        var t = 0.0
        repeat(5) {
            t += 16.7
            ScriptRuntime.runProcessFrame(program, obj, ctx(t, 16.7))
        }

        // process 末尾按 vars.gain（8）× size（1）写视图变换，说明整支脚本真的跑到最后一行
        assertEquals(8.0, view.scale, 1e-12)
        // 末尾写粒子：段长/线宽写进 scale，取向写进 rotation
        assertTrue(particles.any { it.scaleDim != 0 }, "process 应当写出本帧新增的段")
        assertTrue(particles.all { it.pos.all(Double::isFinite) && it.scale.all(Double::isFinite) })
    }

    companion object {
        /** 示波器工程 fx1 的 vars 默认值（base 值，无关键帧）。 */
        val FX1_VARS: Map<String, Double> = mapOf(
            "winMs" to 12.0, "segMs" to 0.0104166667, "segsMax" to 4000.0, "size" to 1.0,
            "halfW" to 3.3, "halfH" to 2.45, "fill" to 1.85, "gain" to 8.0,
            "dcCut" to 1.0, "dcTauMs" to 200.0, "lineW" to 0.008, "overlap" to 1.15,
            "bright" to 0.3, "velPow" to 1.0, "ageMix" to 0.0, "tauMs" to 6.0,
            "cutExp" to 2.0, "slopeRef" to 2.4, "velMin" to 0.05, "glowOn" to 1.0,
            "fillFrames" to 64.0,
        )
    }
}
