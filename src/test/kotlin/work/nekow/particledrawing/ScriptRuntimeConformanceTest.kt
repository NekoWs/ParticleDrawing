package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.ParticleHost
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.parseProgram
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * spawn 模型运行时回归测试：setup/tick/process 入口 + ScriptCtx + 简单 ParticleHost。
 * 语义与编辑器 src/core/script-lang.js + generators.js 对齐。
 */
class ScriptRuntimeConformanceTest {

    private class TestHost(
        override val index: Int,
        private val onKill: (TestHost) -> Unit,
    ) : ParticleHost {
        override val pos = DoubleArray(3)
        override val color = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
        override val vel = DoubleArray(3)
        override var scale = 1.0
        override var glow = false
        override var light = 0.0
        override var life = -1.0
        override val fields = HashMap<String, Any?>()
        var killed = false

        override fun kill() {
            if (killed) return
            killed = true
            onKill(this)
        }
    }

    private class Harness(
        source: String,
        seed: Int = 0,
        private val fastMath: Boolean = false,
        private val duration: Double = 100.0,
    ) {
        val program = parseProgram(source)
        val obj = ScriptRuntime.createObjectState(seed)
        val particles = ArrayList<ParticleHost>()
        var serial = 0

        fun spawn(): ParticleHost {
            val h = TestHost(serial++) { host -> particles.remove(host) }
            particles.add(h)
            return h
        }

        private fun ctx(t: Double = 0.0, deltaMs: Double = 0.0): ScriptRuntime.ScriptCtx =
            ScriptRuntime.ScriptCtx(t, duration, emptyMap(), particles, ::spawn, deltaMs, fastMath, {})

        fun setup(t: Double = 0.0) {
            ScriptRuntime.runSpawnSetup(program, obj, ctx(t))
        }

        fun tick(t: Double = 1.0) {
            ScriptRuntime.runTickFrame(program, obj, ctx(t))
        }

        fun process(t: Double = 0.0, deltaMs: Double = 0.0) {
            ScriptRuntime.runProcessFrame(program, obj, ctx(t, deltaMs))
        }
    }

    @Test
    fun particleFieldReadWrite() {
        val h = Harness(
            "func setup() { p = this.spawn(); p.position = [1,2,3]; p.velocity = [4,5,6]; p.color = vec3(0.5, 0.25, 0.125); p.scale = 0.5; p.glow = 1; p.light = 12; p.life = 7; p.foo = 9; }",
        )
        h.setup()
        assertEquals(1, h.particles.size)
        val host = h.particles[0] as TestHost
        assertEquals(listOf(1.0, 2.0, 3.0), host.pos.toList())
        assertEquals(listOf(4.0, 5.0, 6.0), host.vel.toList())
        assertEquals(listOf(0.5, 0.25, 0.125, 1.0), host.color.toList())
        assertEquals(0.5, host.scale, 1e-12)
        assertEquals(true, host.glow)
        assertEquals(12.0, host.light, 1e-12)
        assertEquals(7.0, host.life, 1e-12)
        assertEquals(9.0, host.fields["foo"])
        assertEquals(0.0, host.fields["bar"] ?: 0.0)
    }

    @Test
    fun forOfAndKill() {
        val h = Harness(
            "func setup() { this.spawn(); this.spawn(); this.spawn(); }\n" +
                "func process(delta) { for (const p of this.particles) { if (p.index == 1) { p.kill(); } } }",
        )
        h.setup()
        assertEquals(3, h.particles.size)
        h.process()
        assertEquals(2, h.particles.size)
        assertEquals(listOf(0, 2), h.particles.map { (it as TestHost).index })
    }

    @Test
    fun particleListSizeAndIndexAccess() {
        val h = Harness(
            "func setup() { this.spawn(); this.spawn(); }\n" +
                "func process(delta) { p = this.spawn(); p.position.x = this.particles.size(); p.position.y = this.particles[0].index; }",
        )
        h.setup()
        h.process()
        val host = h.particles[2] as TestHost
        assertEquals(3.0, host.pos[0], 1e-12)
        assertEquals(0.0, host.pos[1], 1e-12)
    }

    @Test
    fun controlFlow() {
        val h = Harness(
            "func process(delta) { s = 0; for (k = 0; k < 5; k = k + 1) { s = s + k; } while (s < 11) { s = s + 1; } p = this.spawn(); if (s > 10) { p.position.x = s; } else { p.position.x = -1; } p.position.y = (s == 12) ? 2 : 0; }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(11.0, host.pos[0], 1e-12)
        assertEquals(0.0, host.pos[1], 1e-12)
    }

    @Test
    fun funcRecursion() {
        val h = Harness(
            "func fib(nn) { if (nn < 2) { return nn; } return fib(nn-1) + fib(nn-2); }\n" +
                "func fac(nn) { if (nn <= 1) { return 1; } return nn * fac(nn-1); }\n" +
                "func process(delta) { p = this.spawn(); p.position.x = fib(6); p.position.y = fac(4); }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(8.0, host.pos[0], 1e-12)
        assertEquals(24.0, host.pos[1], 1e-12)
    }

    @Test
    fun vecMat() {
        val h = Harness(
            "func process(delta) { p = this.spawn(); v = vec(1,2,3); m = rotZ(pi/2); w = m * v; p.position = w; p.color = [len(w)/4, dot(v,w)/12, cross(v,w).y/10, 1]; }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(-2.0, host.pos[0], 1e-12)
        assertEquals(1.0, host.pos[1], 1e-12)
        assertEquals(3.0, host.pos[2], 1e-12)
        assertEquals(0.9354143466934853, host.color[0], 1e-12)
        assertEquals(0.75, host.color[1], 1e-12)
        assertEquals(0.0, host.color[2], 1e-12) // -0.9 经 clamp01 到 0
    }

    @Test
    fun noiseRandSeeded() {
        val h0 = Harness(
            "func process(delta) { p = this.spawn(); p.position.x = noise(p.index, 0.5, 1.5) * 10; p.position.y = rand() * 10; p.position.z = rand(7) * 10; }",
            seed = 42,
        )
        h0.process()
        val a = h0.particles[0] as TestHost
        assertEquals(6.146115226337443, a.pos[0], 1e-12)
        assertEquals(6.011037519201636, a.pos[1], 1e-12)
        assertEquals(0.11704753153026104, a.pos[2], 1e-12)

        // 同一 seed 下 rand() 序列一致
        val h1 = Harness(
            "func process(delta) { p = this.spawn(); p.position.x = noise(p.index, 0.5, 1.5) * 10; p.position.y = rand() * 10; p.position.z = rand(7) * 10; }",
            seed = 42,
        )
        h1.process()
        val b = h1.particles[0] as TestHost
        assertEquals(a.pos[0], b.pos[0], 1e-12)
        assertEquals(a.pos[1], b.pos[1], 1e-12)
        assertEquals(a.pos[2], b.pos[2], 1e-12)
    }

    @Test
    fun setupAllowsGlobals() {
        val h = Harness("func setup() { global x = 3; global i = 7; global dt = 0.5; }")
        h.setup()
        assertEquals(3.0, h.obj.globals["x"])
        assertEquals(7.0, h.obj.globals["i"])
        assertEquals(0.5, h.obj.globals["dt"])
    }

    @Test
    fun particleFieldsNotShadowedBySetupGlobals() {
        val h = Harness(
            "func setup() { global position = 99; global index = 88; }\n" +
                "func process(delta) { p = this.spawn(); p.position = [5,5,5]; p.position.y = p.position.x; p.position.z = p.index; }",
        )
        h.setup()
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(5.0, host.pos[0], 1e-12)
        assertEquals(5.0, host.pos[1], 1e-12)
        assertEquals(0.0, host.pos[2], 1e-12)
    }

    @Test
    fun functionNamesCanBeVariables() {
        val h = Harness(
            "func process(delta) { sin = 3; p = this.spawn(); p.position.x = sin; p.position.y = sin(1); }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(3.0, host.pos[0], 1e-12)
        assertEquals(sin(1.0), host.pos[1], 1e-12)
    }

    @Test
    fun fastMathUsesFastBuiltinsInProcess() {
        val h = Harness(
            "func process(delta) { p = this.spawn(); p.position.x = sin(0.5); p.position.y = exp(1); p.position.z = atan(1); }",
            fastMath = true,
        )
        h.process()
        val host = h.particles[0] as TestHost
        // 期望值为编辑器 JS fastmath.js 逐位对拍生成的参考值。
        assertEquals(0.479425538604203, host.pos[0], 0.0)
        assertEquals(2.71828182442294, host.pos[1], 0.0)
        assertEquals(0.7854079449038646, host.pos[2], 0.0)
    }

    @Test
    fun particleLifeWritable() {
        val h = Harness(
            "func process(delta) { p = this.spawn(); p.position.x = p.life; p.life = 40.6; p.position.y = p.life; p.life = -3; p.position.z = p.life; }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(-1.0, host.pos[0], 1e-12)
        assertEquals(41.0, host.pos[1], 1e-12)
        assertEquals(-1.0, host.pos[2], 1e-12)
        assertEquals(-1.0, host.life, 1e-12)
    }

    @Test
    fun durationFieldReadableInSetupAndProcess() {
        val h = Harness(
            "func setup() { global d = this.duration; }\n" +
                "func process(delta) { p = this.spawn(); p.position.x = d; p.position.y = this.duration; }",
            duration = 40.0,
        )
        h.setup()
        assertEquals(40.0, h.obj.globals["d"])
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(40.0, host.pos[0], 1e-12)
        assertEquals(40.0, host.pos[1], 1e-12)
    }

    @Test
    fun processParamIsDeltaMs() {
        val h = Harness("func process(dt) { p = this.spawn(); p.position.x = dt; }")
        h.process(t = 5.0, deltaMs = 50.0)
        val host = h.particles[0] as TestHost
        assertEquals(50.0, host.pos[0], 1e-12)
    }

    @Test
    fun printAvailableInAllPhases() {
        val program = parseProgram("func setup() { print(1, 2); }\nfunc process(delta) { print(3); }")
        val obj = ScriptRuntime.createObjectState(0)
        val particles = ArrayList<ParticleHost>()
        var serial = 0
        fun spawn(): ParticleHost { val p = TestHost(serial++) { }; particles.add(p); return p }
        val lines = ArrayList<String>()
        val ctx = ScriptRuntime.ScriptCtx(0.0, 100.0, emptyMap(), particles, ::spawn, 0.0, false, { lines.add(it) })
        ScriptRuntime.runSpawnSetup(program, obj, ctx)
        ScriptRuntime.runProcessFrame(program, obj, ctx)
        assertEquals(listOf("1 2", "3"), lines)
    }
}