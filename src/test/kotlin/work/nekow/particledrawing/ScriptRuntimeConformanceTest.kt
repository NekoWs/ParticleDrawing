package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.TextChar
import work.nekow.particledrawing.animation.TextObject
import work.nekow.particledrawing.animation.script.AudioValue
import work.nekow.particledrawing.animation.script.ParticleHost
import work.nekow.particledrawing.animation.script.ScriptAudio
import work.nekow.particledrawing.animation.script.ScriptException
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.TextValue
import work.nekow.particledrawing.animation.script.Vec2
import work.nekow.particledrawing.animation.script.ViewTransform
import work.nekow.particledrawing.animation.script.parseProgram
import work.nekow.particledrawing.api.Color
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * spawn 模型运行时的语义契约：setup / tick / process 三个入口，以及 ScriptCtx 与 ParticleHost 的行为。
 */
class ScriptRuntimeConformanceTest {

    private class TestHost(
        override val index: Int,
        private val onKill: (TestHost) -> Unit,
    ) : ParticleHost {
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
        /** 与播放端一致：同一个函数对象全程复用同一份视图变换状态（跨 ctx 调用保持）。 */
        val view = ViewTransform()

        fun spawn(): ParticleHost {
            val h = TestHost(serial++) { host -> particles.remove(host) }
            particles.add(h)
            return h
        }

        private fun ctx(t: Double = 0.0, deltaMs: Double = 0.0): ScriptRuntime.ScriptCtx =
            ScriptRuntime.ScriptCtx(t, duration, emptyMap(), particles, ::spawn, deltaMs, fastMath, {}, view = view)

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
            "func setup() { let p = this.spawn(); p.position = [1,2,3]; p.velocity = [4,5,6]; p.color = vec3(0.5, 0.25, 0.125); p.scale = 0.5; p.glow = 1; p.light = 12; p.life = 7; p.foo = 9; }",
        )
        h.setup()
        assertEquals(1, h.particles.size)
        val host = h.particles[0] as TestHost
        assertEquals(listOf(1.0, 2.0, 3.0), host.pos.toList())
        assertEquals(listOf(4.0, 5.0, 6.0), host.vel.toList())
        assertEquals(listOf(0.5, 0.25, 0.125, 1.0), host.color.toList())
        assertEquals(0.5, host.scale[0], 1e-12)
        assertEquals(0.5, host.scale[1], 1e-12)
        assertEquals(1.0, host.scale[2], 1e-12)
        assertEquals(true, host.glow)
        assertEquals(12.0, host.light, 1e-12)
        assertEquals(7.0, host.life, 1e-12)
        assertEquals(9.0, host.fields["foo"])
        assertEquals(0.0, host.fields["bar"] ?: 0.0)
    }

    @Test
    fun particleScaleAcceptsVector() {
        val h = Harness(
            "func setup() { let a = this.spawn(); a.scale = vec2(2, 0.05); " +
                "let b = this.spawn(); b.scale = [3, 0.1]; " +
                "let c = this.spawn(); c.scale = vec3(4, 5, 6); " +
                "let d = this.spawn(); d.scale = 0.5; }",
        )
        h.setup()
        // vec2：[长, 宽]，z 视为 1
        assertEquals(listOf(2.0, 0.05, 1.0), (h.particles[0] as TestHost).scale.toList())
        // 2 元数组与 vec2 同义
        assertEquals(listOf(3.0, 0.1, 1.0), (h.particles[1] as TestHost).scale.toList())
        assertEquals(listOf(4.0, 5.0, 6.0), (h.particles[2] as TestHost).scale.toList())
        // 标量写法：X/Y 同值，Z 恒为 1
        assertEquals(listOf(0.5, 0.5, 1.0), (h.particles[3] as TestHost).scale.toList())
    }

    @Test
    fun spawnConfigScaleAcceptsVector() {
        val h = Harness(
            "func setup() { this.spawn({ scale: vec2(2, 0.05) }); this.spawn({ scale: [3, 0.1] }); this.spawn({ scale: 0.5 }); }",
        )
        h.setup()
        assertEquals(listOf(2.0, 0.05, 1.0), (h.particles[0] as TestHost).scale.toList())
        assertEquals(listOf(3.0, 0.1, 1.0), (h.particles[1] as TestHost).scale.toList())
        assertEquals(listOf(0.5, 0.5, 1.0), (h.particles[2] as TestHost).scale.toList())
    }

    @Test
    fun particleScaleReadsBackTheWrittenShape() {
        val h = Harness(
            "func setup() { let a = this.spawn(); a.scale = vec2(2, 0.05); a.asIs = a.scale; " +
                "let b = this.spawn(); b.scale = 0.75; b.asIs = b.scale; }",
        )
        h.setup()
        // 写向量读回 vec3（vec2 写入时 z 已是 1），写标量读回标量
        assertEquals(work.nekow.particledrawing.animation.script.Vec3(2.0, 0.05, 1.0), (h.particles[0] as TestHost).fields["asIs"])
        assertEquals(0.75, (h.particles[1] as TestHost).fields["asIs"])
    }

    @Test
    fun particleScaleRejectsBadShape() {
        assertFailsWith<ScriptException> {
            Harness("func setup() { let p = this.spawn(); p.scale = \"big\"; }").setup()
        }
        assertFailsWith<ScriptException> {
            Harness("func setup() { let p = this.spawn(); p.scale = [1, 2, 3, 4]; }").setup()
        }
        assertFailsWith<ScriptException> {
            Harness("func setup() { this.spawn({ scale: vec4(1,2,3,4) }); }").setup()
        }
    }

    @Test
    fun viewTransformDefaultsAndWrites() {
        val h = Harness(
            "func setup() { let p = this.spawn(); p.d0 = this.viewScale; p.o0 = this.viewOffset; " +
                "this.viewScale = 2.5; this.viewOffset = vec2(1, -2); p.d1 = this.viewScale; p.o1 = this.viewOffset; }",
        )
        h.setup()
        val host = h.particles[0] as TestHost
        // 默认 1 / 0
        assertEquals(1.0, host.fields["d0"])
        assertEquals(work.nekow.particledrawing.animation.script.Vec3(0.0, 0.0, 0.0), host.fields["o0"])
        // 写后读回拿到自己设的值；vec2 的 z 视为 0
        assertEquals(2.5, host.fields["d1"])
        assertEquals(work.nekow.particledrawing.animation.script.Vec3(1.0, -2.0, 0.0), host.fields["o1"])
        // 同时落到对象级状态（播放端逐 tick 从这里取）
        assertEquals(2.5, h.view.scale, 1e-12)
        assertEquals(listOf(1.0, -2.0, 0.0), h.view.offset.toList())
    }

    @Test
    fun viewTransformSharesStateAcrossPhases() {
        val h = Harness(
            "func setup() { this.viewScale = 4; }\n" +
                "func process() { let p = this.spawn(); p.d = this.viewScale; p.o = this.viewOffset; }",
        )
        h.setup()
        h.process()
        assertEquals(4.0, (h.particles[0] as TestHost).fields["d"])
        assertEquals(work.nekow.particledrawing.animation.script.Vec3(0.0, 0.0, 0.0), (h.particles[0] as TestHost).fields["o"])
    }

    @Test
    fun viewTransformAcceptsVec3AndArrayOffset() {
        val h = Harness("func setup() { this.viewOffset = vec3(1, 2, 3); }")
        h.setup()
        assertEquals(listOf(1.0, 2.0, 3.0), h.view.offset.toList())

        // 3 元数组与 vec3 同义；vec2 也收（z 记 0）
        val h2 = Harness("func setup() { this.viewOffset = [4, 5, 6]; }")
        h2.setup()
        assertEquals(listOf(4.0, 5.0, 6.0), h2.view.offset.toList())

        val h3 = Harness("func setup() { this.viewOffset = vec2(7, 8); }")
        h3.setup()
        assertEquals(listOf(7.0, 8.0, 0.0), h3.view.offset.toList())
    }

    @Test
    fun viewTransformRejectsBadShape() {
        // viewScale 只收有限 num；viewOffset 只收 vec2 / vec3 / 3 元数组
        assertFailsWith<ScriptException> {
            Harness("func setup() { this.viewScale = vec2(1, 2); }").setup()
        }
        assertFailsWith<ScriptException> {
            Harness("func setup() { this.viewScale = 1/0; }").setup()
        }
        assertFailsWith<ScriptException> {
            Harness("func setup() { this.viewOffset = 3; }").setup()
        }
        assertFailsWith<ScriptException> {
            Harness("func setup() { this.viewOffset = [1, 2]; }").setup()
        }
        assertFailsWith<ScriptException> {
            Harness("func setup() { this.viewOffset = [1, 2, 3, 4]; }").setup()
        }
        assertFailsWith<ScriptException> {
            Harness("func setup() { this.viewOffset = vec4(1, 2, 3, 4); }").setup()
        }
    }

    @Test
    fun viewTransformReservedInsideApply() {
        // apply{} 里的 view 成员仍指对象，不落成粒子自定义字段
        val h = Harness("func setup() { let p = this.spawn(); p.apply { this.viewScale = 3; } }")
        h.setup()
        val host = h.particles[0] as TestHost
        assertEquals(3.0, h.view.scale, 1e-12)
        assertTrue(host.fields.isEmpty(), "不该落到粒子的自定义字段上")
    }

    @Test
    fun particleRotationBillboardSpinSpace() {
        val h = Harness(
            "func setup() { let p = this.spawn(); p.rotation = [10, 20, 30]; p.billboard = 0; p.spinSpace = \"world\"; }\n" +
                "func process() { for (const q of this.particles) { q.rotation.y = q.rotation.y + 5; } }",
        )
        h.setup()
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(listOf(10.0, 25.0, 30.0), host.rotation.toList())
        assertEquals(false, host.billboard)
        assertEquals(false, host.spinLocal)
        // billboard 读取与 spinSpace 默认值
        val h2 = Harness("func setup() { let p = this.spawn(); }")
        h2.setup()
        val host2 = h2.particles[0] as TestHost
        assertEquals(true, host2.billboard)
        assertEquals(true, host2.spinLocal)
        assertEquals(listOf(0.0, 0.0, 0.0), host2.rotation.toList())
    }

    @Test
    fun particleComponentAliasesAreCustomFields() {
        val h = Harness(
            "func setup() { let p = this.spawn(); p.a = 1; }\n" +
                "func process() { for (const q of this.particles) { q.b = q.a + 1; q.scale = q.a; } }",
        )
        h.setup()
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(1.0, host.fields["a"])
        assertEquals(2.0, host.fields["b"])
        assertEquals(1.0, host.scale[0], 1e-12)

        // p.color.a / p.position.x 仍是向量分量，不落入自定义字段。
        val h2 = Harness("func setup() { let p = this.spawn(); p.color.a = 0.25; p.position.x = 3; }")
        h2.setup()
        val host2 = h2.particles[0] as TestHost
        assertEquals(0.25, host2.color[3], 1e-12)
        assertEquals(3.0, host2.pos[0], 1e-12)
        assertEquals(0.0, host2.fields["a"] ?: 0.0)
    }

    @Test
    fun colorAlphaAliasReadWrite() {
        // p.color.alpha 是 .a 的别名：读写均落在颜色 alpha，不写入自定义字段。
        val h = Harness(
            "func setup() { let p = this.spawn(); p.color.alpha = 0.25; }\n" +
                "func process() { for (const q of this.particles) { q.scale = q.color.alpha; } }",
        )
        h.setup()
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(0.25, host.color[3], 1e-12)
        assertEquals(0.25, host.scale[0], 1e-12)
        assertEquals(0.0, host.fields["alpha"] ?: 0.0)
    }

    @Test
    fun vec4AlphaAliasIsW() {
        val h = Harness("func setup() { let p = this.spawn(); let v = vec4(1,2,3,4); p.scale = v.alpha; }")
        h.setup()
        val host = h.particles[0] as TestHost
        assertEquals(4.0, host.scale[0], 1e-12)
    }

    @Test
    fun compoundAssignmentOperators() {
        val h = Harness(
            "func setup() { let p = this.spawn(); p.position = [1,2,3]; p.position += vec(4,5,6); p.position.x += 10; p.scale = p.position.x; }",
        )
        h.setup()
        val host = h.particles[0] as TestHost
        assertEquals(listOf(15.0, 7.0, 9.0), host.pos.toList())
        assertEquals(15.0, host.scale[0], 1e-12)

        val h2 = Harness(
            "func setup() { let p = this.spawn(); p.scale = 2; p.scale += 3; p.a = 5; p.a *= 2; }\n" +
                "func process() { for (const q of this.particles) { q.scale -= 1; } let p = this.spawn(); let s = 0; for (let i = 0; i < 5; i += 1) { s += i; } p.scale = s; }",
        )
        h2.setup()
        val h0 = h2.particles[0] as TestHost
        assertEquals(5.0, h0.scale[0], 1e-12)
        assertEquals(10.0, h0.fields["a"])
        h2.process()
        assertEquals(4.0, h0.scale[0], 1e-12)
        assertEquals(10.0, (h2.particles[1] as TestHost).scale[0], 1e-12)
    }

    @Test
    fun forOfAndKill() {
        val h = Harness(
            "func setup() { this.spawn(); this.spawn(); this.spawn(); }\n" +
                "func process() { for (const p of this.particles) { if (p.index == 1) { p.kill(); } } }",
        )
        h.setup()
        assertEquals(3, h.particles.size)
        h.process()
        assertEquals(2, h.particles.size)
        assertEquals(listOf(0, 2), h.particles.map { (it as TestHost).index })
    }

    @Test
    fun particleListSizeAsProperty() {
        // `.size` 属性与 `.size()` 方法等价，数组同理
        val h = Harness(
            "func setup() { this.spawn(); this.spawn(); }\n" +
                "func process() { let p = this.spawn(); let a = [1, 2, 3, 4]; " +
                "p.position.x = this.particles.size; p.position.y = this.particles.size(); p.position.z = a.size; }",
        )
        h.setup()
        h.process()
        val host = h.particles[2] as TestHost
        assertEquals(3.0, host.pos[0], 1e-12)
        assertEquals(3.0, host.pos[1], 1e-12)
        assertEquals(4.0, host.pos[2], 1e-12)
    }

    @Test
    fun listSizeIsReadOnlyAndUnknownMembersFail() {
        assertFailsWith<ScriptException> {
            val h = Harness("func process() { this.particles.size = 1; }")
            h.setup()
            h.process()
        }
        assertFailsWith<ScriptException> {
            val h = Harness("func process() { let n = this.particles.count; }")
            h.setup()
            h.process()
        }
        assertFailsWith<ScriptException> {
            val h = Harness("func setup() { let a = [1, 2]; a.size = 1; }")
            h.setup()
        }
    }

    @Test
    fun particleListSizeAndIndexAccess() {
        val h = Harness(
            "func setup() { this.spawn(); this.spawn(); }\n" +
                "func process() { let p = this.spawn(); p.position.x = this.particles.size(); p.position.y = this.particles[0].index; }",
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
            "func process() { let s = 0; for (let k = 0; k < 5; k = k + 1) { s = s + k; } while (s < 11) { s = s + 1; } let p = this.spawn(); if (s > 10) { p.position.x = s; } else { p.position.x = -1; } p.position.y = (s == 12) ? 2 : 0; }",
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
                "func process() { let p = this.spawn(); p.position.x = fib(6); p.position.y = fac(4); }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(8.0, host.pos[0], 1e-12)
        assertEquals(24.0, host.pos[1], 1e-12)
    }

    @Test
    fun ternaryNewline() {
        val h = Harness(
            "func process() { let p = this.spawn(); p.position.x = true ?\n 1 :\n 2; p.position.y = false\n ? 3\n : 4; }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(1.0, host.pos[0], 1e-12)
        assertEquals(4.0, host.pos[1], 1e-12)
    }

    @Test
    fun vecMat() {
        val h = Harness(
            "func process() { let p = this.spawn(); let v = vec(1,2,3); let w = v.rotateZ(PI/2); p.position = w; p.color = [w.len()/4, v.dot(w)/12, v.cross(w).y/10, 1]; }",
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
            "func process() { let p = this.spawn(); p.position.x = noise(p.index, 0.5, 1.5) * 10; p.position.y = rand() * 10; p.position.z = rand(7) * 10; }",
            seed = 42,
        )
        h0.process()
        val a = h0.particles[0] as TestHost
        assertEquals(6.146115226337443, a.pos[0], 1e-12)
        assertEquals(6.011037519201636, a.pos[1], 1e-12)
        assertEquals(0.11704753153026104, a.pos[2], 1e-12)

        // 同一 seed 下 rand() 序列一致
        val h1 = Harness(
            "func process() { let p = this.spawn(); p.position.x = noise(p.index, 0.5, 1.5) * 10; p.position.y = rand() * 10; p.position.z = rand(7) * 10; }",
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
        val h = Harness("let x = 3\nlet i = 7\nlet dt = 0.5")
        h.setup()
        assertEquals(3.0, h.obj.globals["x"])
        assertEquals(7.0, h.obj.globals["i"])
        assertEquals(0.5, h.obj.globals["dt"])
    }

    @Test
    fun multipleDeclaratorsInOneStatement() {
        val h = Harness("let a = 1, b = a + 1, c = b + a\nconst d = 2, e = d * 3")
        h.setup()
        assertEquals(1.0, h.obj.globals["a"])
        assertEquals(2.0, h.obj.globals["b"])
        assertEquals(3.0, h.obj.globals["c"])
        assertEquals(2.0, h.obj.globals["d"])
        assertEquals(6.0, h.obj.globals["e"])
    }

    @Test
    fun multipleDeclaratorsLocalAndConstReadonly() {
        val h = Harness(
            "func process() { let a = 2, b = 3, c = a + b; let p = this.spawn(); p.position.x = c; }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(5.0, host.pos[0], 1e-12)

        assertFailsWith<ScriptException> {
            Harness("func process() { const a = 1, b = 2; a = 3; }").process()
        }
    }

    @Test
    fun logicalOperatorsReturnOperandValues() {
        val h = Harness(
            "func process() { let a = 5 || 7; let b = 0 || 9; let c = 3 && 11; let d = 0 && 13; let p = this.spawn(); p.position.x = a; p.position.y = b; p.position.z = c; p.scale = d; }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(5.0, host.pos[0], 1e-12)
        assertEquals(9.0, host.pos[1], 1e-12)
        assertEquals(11.0, host.pos[2], 1e-12)
        assertEquals(0.0, host.scale[0], 1e-12)
    }

    @Test
    fun divisionGuardWithOrReturnsNumber() {
        val h = Harness(
            "let n = 1\nfunc process() { let p = this.spawn(); p.position.x = 4 / (n - 1 || 1); }",
        )
        h.setup()
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(4.0, host.pos[0], 1e-12)
    }

    @Test
    fun particleFieldsNotShadowedBySetupGlobals() {
        val h = Harness(
            "let position = 99\nlet index = 88\n" +
                "func process() { let p = this.spawn(); p.position = [5,5,5]; p.position.y = p.position.x; p.position.z = p.index; }",
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
            "func process() { let sin = 3; let p = this.spawn(); p.position.x = sin; p.position.y = sin(1); }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(3.0, host.pos[0], 1e-12)
        assertEquals(sin(1.0), host.pos[1], 1e-12)
    }

    @Test
    fun fastMathUsesFastBuiltinsInProcess() {
        val h = Harness(
            "func process() { let p = this.spawn(); p.position.x = sin(0.5); p.position.y = exp(1); p.position.z = atan(1); }",
            fastMath = true,
        )
        h.process()
        val host = h.particles[0] as TestHost
        // 与编辑器逐位一致：参考值固定，容差为 0
        assertEquals(0.479425538604203, host.pos[0], 0.0)
        assertEquals(2.71828182442294, host.pos[1], 0.0)
        assertEquals(0.7854079449038646, host.pos[2], 0.0)
    }

    @Test
    fun particleLifeWritable() {
        val h = Harness(
            "func process() { let p = this.spawn(); p.position.x = p.life; p.life = 40.6; p.position.y = p.life; p.life = -3; p.position.z = p.life; }",
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
            "let d = 0\nfunc setup() { d = this.duration; }\n" +
                "func process() { let p = this.spawn(); p.position.x = d; p.position.y = this.duration; }",
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
    fun deltaFieldReadableInAllPhases() {
        // this.delta：距上一次跑 process/tick 的毫秒数，三个阶段同名同义，未给时读 0
        val h = Harness(
            "let d = -1.0\n" +
                "func setup() { d = this.delta; }\n" +
                "func tick() { d = this.delta; }\n" +
                "func process() { let p = this.spawn(); p.position.x = this.delta; p.position.y = this.delta + d; }",
        )
        h.setup()
        assertEquals(0.0, h.obj.globals["d"])
        h.tick(t = 1.0)
        assertEquals(0.0, h.obj.globals["d"])
        h.process(t = 0.0, deltaMs = 16.7)
        val host = h.particles[0] as TestHost
        assertEquals(16.7, host.pos[0], 1e-12)
        // process 里读到的是本次给的 16.7，d 是 tick（默认 0）时留下的
        assertEquals(16.7, host.pos[1], 1e-12)
    }

    @Test
    fun deltaDefaultsToZeroWithoutCallerValue() {
        // 没给 delta（首帧、setup 阶段）时读 0
        val h = Harness("func process() { let p = this.spawn(); p.position.x = this.delta; }")
        h.process()
        assertEquals(0.0, (h.particles[0] as TestHost).pos[0], 1e-12)
    }

    @Test
    fun processTakesNoParameters() {
        val h = Harness("func process() { let p = this.spawn(); p.position.x = this.duration; }", duration = 7.0)
        h.process(t = 5.0, deltaMs = 50.0)
        val host = h.particles[0] as TestHost
        assertEquals(7.0, host.pos[0], 1e-12)

        // process 不接受参数
        assertFailsWith<RuntimeException> { parseProgram("func process(dt) {}") }
    }

    @Test
    fun printAvailableInAllPhases() {
        val program = parseProgram("func setup() { print(1, 2); }\nfunc process() { print(3); }")
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

    @Test
    fun spawnConfigAppliesKnownFields() {
        val h = Harness(
            "func setup() { let p = this.spawn({ position: [1,2,3], velocity: vec3(4,5,6), color: vec3(0.5, 0.25, 0.125), scale: 2, glow: 1, light: 5, life: 3, uv: [0.1, 0.2] }); }",
        )
        h.setup()
        val host = h.particles[0] as TestHost
        assertEquals(listOf(1.0, 2.0, 3.0), host.pos.toList())
        assertEquals(listOf(4.0, 5.0, 6.0), host.vel.toList())
        assertEquals(listOf(0.5, 0.25, 0.125, 1.0), host.color.toList())
        assertEquals(2.0, host.scale[0], 1e-12)
        assertEquals(true, host.glow)
        assertEquals(5.0, host.light, 1e-12)
        assertEquals(3.0, host.life, 1e-12)
        assertEquals(listOf(0.1, 0.2), host.fields["uv"])
    }

    @Test
    fun spawnConfigAcceptsCustomFields() {
        // spawn 配置里的未知键作为粒子的命名标量存进 fields，脚本里用 p.键名 读写
        val h = Harness("func setup() { let p = this.spawn({ foo: 1, bar: [2,3,4] }); p.alpha = p.foo + 1 }")
        h.setup()
        val host = h.particles[0] as TestHost
        assertEquals(1.0, host.fields["foo"])
        assertEquals(listOf(2.0, 3.0, 4.0), host.fields["bar"])
        assertEquals(2.0, host.fields["alpha"])
    }

    @Test
    fun applyThisBindsToReceiver() {
        val h = Harness(
            "func setup() { let p = this.spawn(); let r = p.apply { this.position = [4,5,6]; this.scale = 2 }; r.life = 7 }",
        )
        h.setup()
        val host = h.particles[0] as TestHost
        assertEquals(listOf(4.0, 5.0, 6.0), host.pos.toList())
        assertEquals(2.0, host.scale[0], 1e-12)
        assertEquals(7.0, host.life, 1e-12)
    }

    @Test
    fun vecMethodChainAndMultiline() {
        val h = Harness(
            "func process() { let p = this.spawn(); let w = vec(1,0,0).rotateZ(PI/2)\n  .translate(0, 0, 5); p.position = w; }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(0.0, host.pos[0], 1e-12)
        assertEquals(1.0, host.pos[1], 1e-12)
        assertEquals(5.0, host.pos[2], 1e-12)
    }

    @Test
    fun colorMethodsAndConversions() {
        val h = Harness(
            "func process() { let p = this.spawn(); let c = color(1,0,0,1).green(0.5); p.position.x = c.red(); p.position.y = c.toRGB().g; p.position.z = c.toHSV().v; p.scale = c.alpha(); }",
        )
        h.process()
        val host = h.particles[0] as TestHost
        assertEquals(1.0, host.pos[0], 1e-12)
        assertEquals(0.5, host.pos[1], 1e-12)
        assertEquals(1.0, host.pos[2], 1e-12)
        assertEquals(1.0, host.scale[0], 1e-12)
    }

    @Test
    fun textAssetGetReadOnly() {
        val program = parseProgram(
            "let out = 0\nfunc process() { let t = this.get(\"title\"); out = [t.name, t.text, t.st, t.chars.size(), t.chars[0].index, t.chars[0].code, t.chars[0].pos, t.chars[0].size, t.chars[0].particles]; }",
        )
        val obj = ScriptRuntime.createObjectState(0)
        val particles = ArrayList<ParticleHost>()
        val text = TextObject(
            id = "txt1", name = "title", text = "Hi", font = "monospace", fontSize = 24, weight = "normal",
            italic = false, color = Color.WHITE, strokeColor = null, strokeWidth = 0,
            align = "left", lineHeight = 1.0, letterSpacing = 0.0, st = 3, life = 20,
            chars = listOf(TextChar(0, 72, Vec3(1.0, 2.0, 3.0), doubleArrayOf(0.4, 0.6), listOf("p0"))),
        )
        val ctx = ScriptRuntime.ScriptCtx(
            t = 0.0, duration = 100.0, vars = emptyMap(), particles = particles,
            spawn = { error("no spawn") },
            get = { name -> if (name == "title") TextValue(text) else throw ScriptException("unknown asset '$name'") },
        )
        ScriptRuntime.runTopLevel(program, obj, ctx)
        ScriptRuntime.runProcessFrame(program, obj, ctx)
        val out = obj.globals["out"] as MutableList<*>
        assertEquals("title", out[0])
        assertEquals("Hi", out[1])
        assertEquals(3.0, out[2])
        assertEquals(1.0, out[3])
        assertEquals(0.0, out[4])
        assertEquals(72.0, out[5])
        assertEquals(work.nekow.particledrawing.animation.script.Vec3(1.0, 2.0, 3.0), out[6])
        assertEquals(Vec2(0.4, 0.6), out[7])
        assertEquals(listOf("p0"), out[8])
    }

    @Test
    fun textAssetUnknownThrows() {
        val program = parseProgram("func process() { let t = this.get(\"nope\"); }")
        val obj = ScriptRuntime.createObjectState(0)
        val ctx = ScriptRuntime.ScriptCtx(
            t = 0.0, duration = 100.0, vars = emptyMap(), particles = ArrayList(),
            spawn = { error("no spawn") },
            get = { throw ScriptException("unknown asset") },
        )
        ScriptRuntime.runTopLevel(program, obj, ctx)
        assertFailsWith<ScriptException> {
            ScriptRuntime.runProcessFrame(program, obj, ctx)
        }
    }

    private fun makeAudioAsset() = AudioAsset(
        id = "aud1", name = "bgm", fmt = 0, data = ByteArray(4) { it.toByte() },
        st = 100, durMs = 4000, hopCount = 4,
        bpm = 128.0, beatOffsetMs = 0.0, onsetMax = 0.5,
        beats = listOf(0, 469),
        rms = shortArrayOf(0, -32768, -1, -32768),   // u16：32768 = 0x8000
        peak = shortArrayOf(-1, -1, -1, -1),
        centroid = shortArrayOf(0, 16384, -32768, 16384),
        onset = byteArrayOf(0, -128, -1, -128),
        rolloff = byteArrayOf(0, 64, -128, 64),
        bands = ByteArray(4 * 16) { (it / 16 * 32 + it % 16).toByte() },
    )

    @Test
    fun audioAssetGetFieldsAndBand() {
        val program = parseProgram(
            "let out = 0\nfunc process() { let a = this.get(\"bgm\"); out = [a.name, a.st, a.length, a.progress, a.playing, a.loud, a.band(3), a.bpm, a.beats, a.onset]; }",
        )
        val obj = ScriptRuntime.createObjectState(0)
        val audio = makeAudioAsset()
        val ctx = ScriptRuntime.ScriptCtx(
            t = 150.0, duration = 5000.0, vars = emptyMap(), particles = ArrayList(),
            spawn = { error("no spawn") },
            get = { name -> if (name == "bgm") AudioValue(audio, 150.0, false) else throw ScriptException("unknown asset") },
        )
        ScriptRuntime.runTopLevel(program, obj, ctx)
        ScriptRuntime.runProcessFrame(program, obj, ctx)
        val out = obj.globals["out"] as MutableList<*>
        assertEquals("bgm", out[0])
        assertEquals(100.0, out[1])
        assertEquals(4000.0, out[2])
        assertEquals(50.0, out[3] as Double, 1e-9)
        assertEquals(false, out[4])
        // t=150 → 本地 50ms，hop 宽 1000ms，插值权重 0.05
        assertEquals((32768 * 0.05) / 65535.0, out[5] as Double, 1e-9)
        assertEquals((3 * 0.95 + 35 * 0.05) / 255.0, out[6] as Double, 1e-9)
        assertEquals(128.0, out[7])
        assertEquals(listOf(0.0, 469.0), out[8])
        assertEquals((128 * 0.05) / 255.0 * 0.5, out[9] as Double, 1e-9)
    }

    @Test
    fun audioSpeedScalesContentPosition() {
        // 内容位置 = (t - st) × speed，钳到 [0, durMs]
        val program = parseProgram(
            "let out = 0\nfunc process() { let a = this.get(\"bgm\"); out = [a.progress, a.loud]; }",
        )
        val obj = ScriptRuntime.createObjectState(0)
        val audio = makeAudioAsset()
        val ctx = ScriptRuntime.ScriptCtx(
            t = 150.0, duration = 5000.0, vars = emptyMap(), particles = ArrayList(),
            spawn = { error("no spawn") },
            get = { name -> if (name == "bgm") AudioValue(audio, 150.0, true, 2.5) else throw ScriptException("unknown asset") },
        )
        ScriptRuntime.runTopLevel(program, obj, ctx)
        ScriptRuntime.runProcessFrame(program, obj, ctx)
        val out = obj.globals["out"] as MutableList<*>
        assertEquals(125.0, out[0] as Double, 1e-9)
        // hop 宽 1000ms，插值权重 0.125
        assertEquals((32768 * 0.125) / 65535.0, out[1] as Double, 1e-9)
    }

    @Test
    fun scriptAudioInterpMatchesEditor() {
        // 与编辑器逐位一致：hop 宽 1000ms，t=500 → f=0.5
        val v = ScriptAudio.valueAt(makeAudioAsset(), 500.0)
        assertEquals((0.0 + 32768.0) / 2 / 65535.0, v.rms, 1e-12)
        assertEquals(1.0, v.peak, 1e-12)
        assertEquals((0.0 + 16384.0) / 2 / 65535.0 * 22050.0, v.centroid, 1e-6)
        assertEquals((0.0 + 128.0) / 2 / 255.0 * 0.5, v.onset, 1e-12)
        assertEquals((2.0 + 34.0) / 2 / 255.0, v.bands[2], 1e-12)
        // 越界钳制到末 hop
        assertEquals(32768.0 / 65535.0, ScriptAudio.valueAt(makeAudioAsset(), 5000.0).rms, 1e-12)
    }

    @Test
    fun scriptAudioWindowSharesDurationWithValueAt() {
        val a = makeAudioAsset()
        assertTrue(ScriptAudio.windowAt(a, 100.0))
        assertTrue(ScriptAudio.windowAt(a, 4099.0))
        assertFalse(ScriptAudio.windowAt(a, 4100.0))
        assertFalse(ScriptAudio.windowAt(a, 99.0))
        // durMs <= 0：时长按 hop 数推算（与 valueAt 的查表跨度同一口径），不再是恒空窗口
        val noDur = AudioAsset(
            id = "aud2", name = "n", fmt = 0, data = ByteArray(0), st = 0, durMs = 0, hopCount = 4,
            bpm = 0.0, beatOffsetMs = 0.0, onsetMax = 1.0, beats = emptyList(),
            rms = ShortArray(4), peak = ShortArray(4), centroid = ShortArray(4),
            onset = ByteArray(4), rolloff = ByteArray(4), bands = ByteArray(4 * 16),
        )
        assertEquals(4 * ScriptAudio.HOP_MS, ScriptAudio.durationMs(noDur), 1e-9)
        assertTrue(ScriptAudio.windowAt(noDur, 0.0))
        assertTrue(ScriptAudio.windowAt(noDur, 46.0))
        assertFalse(ScriptAudio.windowAt(noDur, 47.0))
    }
}
