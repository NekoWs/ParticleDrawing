package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.ParticleHost
import work.nekow.particledrawing.animation.script.ScriptException
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.ViewTransform
import work.nekow.particledrawing.animation.script.parseProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 循环里的 return / 顶层 return 的回归测试。
 *
 * 编辑器侧踩过一次：循环体里出现 return 会写坏字节码，runProcessFrame 抛 unknown bytecode op 后
 * 静默回退 AST —— 脚本没坏，只是慢。播放端是另一套实现（树遍历解释器，没有字节码也没有回退后路），
 * 所以这里单独钉住：循环里的 return 结果要对、不抛错；process 顶层的 return 就是「这一帧到此为止」。
 *
 * 断言只看粒子字段：脚本把结果写进 p.position，测试读出来比对（跑不通会直接抛，测试即红）。
 */
class ScriptLoopReturnTest {

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
        override fun kill() {}
    }

    private class Harness(source: String) {
        val program = parseProgram(source)
        private val obj = ScriptRuntime.createObjectState(0)
        private val particles = ArrayList<ParticleHost>()
        private var serial = 0
        private val view = ViewTransform()

        private fun ctx(t: Double) = ScriptRuntime.ScriptCtx(
            t = t,
            duration = 100.0,
            vars = emptyMap(),
            particles = particles,
            spawn = { TestHost(serial++).also { particles.add(it) } },
            deltaMs = 6.25,
            fastMath = false,
            print = {},
            st = 0.0,
            maxMs = 0.0,
            view = view,
        )

        fun setup(t: Double = 0.0) { ScriptRuntime.runSpawnSetup(program, obj, ctx(t)) }
        fun tick(t: Double = 1.0) { ScriptRuntime.runTickFrame(program, obj, ctx(t)) }
        fun process(t: Double = 0.0) { ScriptRuntime.runProcessFrame(program, obj, ctx(t)) }

        /** 第 i 个粒子的 position.x：脚本把结果写在这里。 */
        fun x(i: Int = 0): Double = particles[i].pos[0]

        /** 第 i 个粒子的 position.y。 */
        fun y(i: Int = 0): Double = particles[i].pos[1]

        val count: Int get() = particles.size
    }

    // —— 用户函数里的循环 return：值要带出来，循环后面的代码不能跑 ——

    @Test
    fun `while 里 return 带值`() {
        val h = Harness(
            "func f() { let a = 0; while (a < 9) { a = a + 1; if (a > 4) { return a * 10; } } return -1; }\n" +
                "func process() { let p = this.spawn(); p.position.x = f(); p.position.y = f(); }",
        )
        h.process()
        // 两次调用都是 50：第一次的提前返回没有把状态/作用域留脏
        assertEquals(50.0, h.x(), 1e-12)
        assertEquals(50.0, h.y(), 1e-12)
    }

    @Test
    fun `while 里 return 不带值且跳过循环后的代码`() {
        val h = Harness(
            "func f(p) { let a = 0; while (a < 9) { a = a + 1; if (a > 4) { return; } p.foo = p.foo + 1; } p.foo = p.foo + 1000; }\n" +
                "func process() { let p = this.spawn(); p.foo = 0; f(p); p.position.x = p.foo; }",
        )
        h.process()
        // a 走到 5 就返回：p.foo 只加到 4；循环后的 p.foo = p.foo + 1000 不能跑（return 不是 break）
        assertEquals(4.0, h.x(), 1e-12)
    }

    @Test
    fun `for 里 return 带值`() {
        val h = Harness(
            "func f() { for (let i = 0; i < 9; i = i + 1) { if (i > 3) { return i * 2; } } return -1; }\n" +
                "func process() { let p = this.spawn(); p.position.x = f(); }",
        )
        h.process()
        assertEquals(8.0, h.x(), 1e-12)
    }

    @Test
    fun `for-of 里 return 带值`() {
        val h = Harness(
            "func f(arr) { for (const v of arr) { if (v > 6) { return v; } } return -1; }\n" +
                "func process() { let p = this.spawn(); p.position.x = f([5, 6, 7, 8]); }",
        )
        h.process()
        assertEquals(7.0, h.x(), 1e-12)
    }

    @Test
    fun `do-while 里 return 带值`() {
        val h = Harness(
            "func f() { let a = 0; do { a = a + 1; if (a > 3) { return a; } } while (a < 20); return -1; }\n" +
                "func process() { let p = this.spawn(); p.position.x = f(); }",
        )
        h.process()
        assertEquals(4.0, h.x(), 1e-12)
    }

    @Test
    fun `return 嵌在两层 if 与更深块里`() {
        val h = Harness(
            "func f() { let a = 0; while (a < 9) { a = a + 1; if (a > 1) { if (a > 3) { { return a + 100; } } } } return -1; }\n" +
                "func process() { let p = this.spawn(); p.position.x = f(); }",
        )
        h.process()
        assertEquals(104.0, h.x(), 1e-12)
    }

    @Test
    fun `循环里的 return 之后循环外的代码不跑`() {
        val h = Harness(
            "func f() { let a = 0; while (a < 9) { a = a + 1; if (a > 4) { return a; } } return -1; }\n" +
                "func process() { let p = this.spawn(); p.position.x = f(); p.position.y = 1; }",
        )
        h.process()
        assertEquals(5.0, h.x(), 1e-12)
        assertEquals(1.0, h.y(), 1e-12)
    }

    @Test
    fun `return 在 when 分支体里也能收住`() {
        val h = Harness(
            "func f(k) { when (k) { 1 -> { return 11; } else -> { } } return 33; }\n" +
                "func process() { let p = this.spawn(); p.position.x = f(1); p.position.y = f(2); }",
        )
        h.process()
        assertEquals(11.0, h.x(), 1e-12)
        assertEquals(33.0, h.y(), 1e-12)
    }

    // —— process 顶层的 return：结束这一帧（与编辑器同一含义） ——

    @Test
    fun `process 顶层 return 结束这一帧`() {
        val h = Harness("func process() { let p = this.spawn(); p.position.x = 1; return; p.position.x = 99; }")
        h.process()
        assertEquals(1.0, h.x(), 1e-12)
    }

    @Test
    fun `process 顶层 return 带值也结束这一帧`() {
        val h = Harness("func process() { let p = this.spawn(); p.position.x = 2; if (true) { return 7; } p.position.x = 99; }")
        h.process()
        assertEquals(2.0, h.x(), 1e-12)
    }

    @Test
    fun `process 顶层循环里 return 结束这一帧而不是只跳出循环`() {
        val h = Harness(
            "func process() { let p = this.spawn(); p.position.x = 1; let a = 0; " +
                "while (a < 9) { a = a + 1; if (a > 2) { return; } } p.position.x = 99; }",
        )
        h.process()
        assertEquals(1.0, h.x(), 1e-12)
    }

    @Test
    fun `process 顶层 for-of 里 return 结束这一帧`() {
        val h = Harness(
            "func process() { let p = this.spawn(); p.position.x = 3; " +
                "for (const v of [5, 6, 7, 8]) { if (v > 6) { return; } } p.position.x = 99; }",
        )
        h.process()
        assertEquals(3.0, h.x(), 1e-12)
    }

    @Test
    fun `连续多帧顶层 return 不残留状态`() {
        val h = Harness("func process() { let p = this.spawn(); p.position.x = this.animTime; return; }")
        h.process()
        h.process(16.0)
        h.process(32.0)
        assertEquals(3, h.count)
        assertEquals(0.0, h.x(0), 1e-12)
        assertEquals(16.0, h.x(1), 1e-12)
        assertEquals(32.0, h.x(2), 1e-12)
    }

    // —— 循环控制：break/continue 与 return 必须互相不串味 ——

    @Test
    fun `循环里的 break 与 continue 仍按循环语义走`() {
        val h = Harness(
            "func process() { let p = this.spawn(); let s = 0; " +
                "for (let i = 0; i < 9; i = i + 1) { if (i == 3) { continue; } if (i > 5) { break; } s = s + i; } p.position.x = s; }",
        )
        h.process()
        // 0+1+2(+跳过 3)+4+5 = 12
        assertEquals(12.0, h.x(), 1e-12)
    }

    @Test
    fun `while 里的 break 与 continue 仍按循环语义走`() {
        val h = Harness(
            "func process() { let p = this.spawn(); let a = 0; let s = 0; " +
                "while (a < 9) { a = a + 1; if (a == 2) { continue; } if (a > 4) { break; } s = s + a; } p.position.x = s; }",
        )
        h.process()
        // 1(+跳过 2)+3+4 = 8
        assertEquals(8.0, h.x(), 1e-12)
    }

    // —— 与编辑器对齐的边界：循环外的 break/continue 两端都是解析期硬拒绝 ——

    @Test
    fun `process 顶层 break 与 continue 解析期报错`() {
        val brk = assertFailsWith<ScriptException> { parseProgram("func process() { break; }") }
        assertTrue(brk.message!!.contains("'break' outside loop"), "实际报错：${brk.message}")
        val cont = assertFailsWith<ScriptException> { parseProgram("func process() { continue; }") }
        assertTrue(cont.message!!.contains("'continue' outside loop"), "实际报错：${cont.message}")
    }

    @Test
    fun `setup 与 tick 顶层 return 仍按错误处理`() {
        // 播放端沿用编辑器的边界：只有 process 顶层的 return 是「这一帧到此为止」，
        // setup/tick 顶层 return 两端都不当合法写法（编辑器那边会抛内部 Flow）。
        val h = Harness("func setup() { let p = this.spawn(); return; }")
        val err = assertFailsWith<ScriptException> { h.setup() }
        assertTrue(err.message!!.contains("return is only allowed inside a function"), "实际报错：${err.message}")
        val h2 = Harness("func tick() { return; }")
        assertFailsWith<ScriptException> { h2.tick() }
    }
}
