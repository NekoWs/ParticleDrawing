package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.parseProgram
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptRuntimeConformanceTest {

    private fun uvFor(count: Int, i: Int, gridCols: Int?): Pair<Double, Double> {
        val C = gridCols ?: maxOf(1, ceil(sqrt(count.toDouble())).toInt())
        val R = maxOf(1, ceil(count.toDouble() / C).toInt())
        val col = i % C
        val row = floor(i.toDouble() / C).toInt()
        val uvX = if (C == 1) 0.0 else col.toDouble() / (C - 1.0)
        val uvY = if (R == 1) 0.0 else row.toDouble() / (R - 1.0)
        return uvX to uvY
    }

    private fun eval(setup: String, process: String, funcs: String, seed: Int, count: Int, i: Int, fastMath: Boolean = false): ScriptRuntime.ScriptOut {
        val program = parseProgram("$funcs\nsetup {\n$setup\n}\nprocess {\n$process\n}\n")
        val obj = ScriptRuntime.createObjectState(seed)
        ScriptRuntime.runSetup(program, obj, ScriptRuntime.SetupEnv(count.toDouble(), 0.0, emptyMap()))
        val statics = ScriptRuntime.createStatics()
        val uv = uvFor(count, i, null)
        val ctx = ScriptRuntime.ProcessCtx(
            i = i.toDouble(), n = count.toDouble(), t = 0.0, dt = 0.0,
            life = 0.0, uv_x = uv.first, uv_y = uv.second, vars = emptyMap(), fastMath = fastMath,
        )
        return ScriptRuntime.evalProcess(program, obj, statics, ctx)
    }

    @Test
    fun attrsArith() {
        val out = eval("", "this.position = [this.index*2, this.index+1, this.count]; this.color.r = this.index/this.count; this.scale = 0.5; this.velocity.x = this.index; this.glow = 1; this.light = 12;", "", 0, 3, 0)
        assertEquals(listOf(0.0, 1.0, 3.0), out.pos.toList())
        assertEquals(listOf(0.0, 1.0, 1.0, 1.0), out.color.toList())
        assertEquals(0.5, out.scale, 1e-12)
        assertEquals(true, out.glow)
        assertEquals(12.0, out.light, 1e-12)
    }

    @Test
    fun arrays() {
        val out = eval(
            "global arr = []; arr.push(3); arr.push(1); arr.push(2); global brr = arr.slice(0, 3);",
            "this.position.x = arr[0]; this.position.y = arr.find(2); this.position.z = arr.includes(9) ? 1 : 0; this.scale = brr.size();",
            "", 0, 1, 0,
        )
        assertEquals(listOf(3.0, 2.0, 0.0), out.pos.toList())
        assertEquals(3.0, out.scale, 1e-12)
    }

    @Test
    fun controlFlow() {
        val out = eval(
            "",
            "s = 0; for (k = 0; k < 5; k = k + 1) { s = s + k; } while (s < 11) { s = s + 1; } if (s > 10) { this.position.x = s; } else { this.position.x = -1; } this.position.y = (s == 12) ? 2 : 0;",
            "", 0, 1, 0,
        )
        assertEquals(listOf(11.0, 0.0, 0.0), out.pos.toList())
    }

    @Test
    fun funcRecursion() {
        val out = eval(
            "",
            "this.position.x = fib(6); this.position.y = fac(4);",
            "func fib(nn) { if (nn < 2) { return nn; } return fib(nn-1) + fib(nn-2); }\nfunc fac(nn) { if (nn <= 1) { return 1; } return nn * fac(nn-1); }",
            0, 1, 0,
        )
        assertEquals(listOf(8.0, 24.0, 0.0), out.pos.toList())
    }

    @Test
    fun vecMat() {
        val out = eval(
            "",
            "v = vec(1,2,3); m = rotZ(pi/2); w = m * v; this.position = w; this.color = [len(w)/4, dot(v,w)/12, cross(v,w).y/10, 1];",
            "", 0, 1, 0,
        )
        assertEquals(-2.0, out.pos[0], 1e-12)
        assertEquals(1.0, out.pos[1], 1e-12)
        assertEquals(3.0, out.pos[2], 1e-12)
        assertEquals(0.9354143466934853, out.color[0], 1e-12)
        assertEquals(0.75, out.color[1], 1e-12)
        assertEquals(0.0, out.color[2], 1e-12)
    }

    @Test
    fun noiseRandSeeded() {
        val out0 = eval("", "this.position.x = noise(this.index, 0.5, 1.5) * 10; this.position.y = rand() * 10; this.position.z = rand(7) * 10;", "", 42, 2, 0)
        assertEquals(6.146115226337443, out0.pos[0], 1e-12)
        assertEquals(6.011037519201636, out0.pos[1], 1e-12)
        assertEquals(0.11704753153026104, out0.pos[2], 1e-12)

        val out1 = eval("", "this.position.x = noise(this.index, 0.5, 1.5) * 10; this.position.y = rand() * 10; this.position.z = rand(7) * 10;", "", 42, 2, 1)
        assertEquals(4.786, out1.pos[0], 1e-12)
        assertEquals(6.011037519201636, out1.pos[1], 1e-12)
        assertEquals(0.11704753153026104, out1.pos[2], 1e-12)
    }

    @Test
    fun setupAllowsAttrAndNonSetupBuiltinNames() {
        val program = parseProgram("setup { global x = 3; global i = 7; global dt = 0.5; }")
        val obj = ScriptRuntime.createObjectState(0)
        ScriptRuntime.runSetup(program, obj, ScriptRuntime.SetupEnv(10.0, 0.0, emptyMap()))
        assertEquals(3.0, obj.globals["x"])
        assertEquals(7.0, obj.globals["i"])
        assertEquals(0.5, obj.globals["dt"])
    }

    @Test
    fun processAttrAndBuiltinNotShadowedBySetupGlobals() {
        val program = parseProgram("setup { global position = 99; global index = 88; } process { this.position = [5, 5, 5]; this.position.x = 5; this.position.y = this.position.x; this.position.z = this.index; }")
        val obj = ScriptRuntime.createObjectState(0)
        ScriptRuntime.runSetup(program, obj, ScriptRuntime.SetupEnv(10.0, 0.0, emptyMap()))
        val statics = ScriptRuntime.createStatics()
        val ctx = ScriptRuntime.ProcessCtx(3.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap())
        val out = ScriptRuntime.evalProcess(program, obj, statics, ctx)
        assertEquals(5.0, out.pos[0], 1e-12)
        assertEquals(5.0, out.pos[1], 1e-12)
        assertEquals(3.0, out.pos[2], 1e-12)
    }

    @Test
    fun functionNamesCanBeVariables() {
        val program = parseProgram("process { sin = 3; this.position.x = sin; this.position.y = sin(1); }")
        val obj = ScriptRuntime.createObjectState(0)
        ScriptRuntime.runSetup(program, obj, ScriptRuntime.SetupEnv(10.0, 0.0, emptyMap()))
        val ctx = ScriptRuntime.ProcessCtx(0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap())
        val out = ScriptRuntime.evalProcess(program, obj, ScriptRuntime.createStatics(), ctx)
        assertEquals(3.0, out.pos[0], 1e-12)
        assertEquals(kotlin.math.sin(1.0), out.pos[1], 1e-12)
    }

    @Test
    fun fastMathUsesFastBuiltinsInProcess() {
        val program = parseProgram("process { this.position.x = sin(0.5); this.position.y = exp(1); this.position.z = atan(1); }")
        val obj = ScriptRuntime.createObjectState(0)
        ScriptRuntime.runSetup(program, obj, ScriptRuntime.SetupEnv(10.0, 0.0, emptyMap()))
        val ctx = ScriptRuntime.ProcessCtx(0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap(), fastMath = true)
        val out = ScriptRuntime.evalProcess(program, obj, ScriptRuntime.createStatics(), ctx)
        // 期望值为编辑器 JS fastmath.js 逐位对拍生成的参考值。
        assertEquals(0.479425538604203, out.pos[0], 0.0)
        assertEquals(2.71828182442294, out.pos[1], 0.0)
        assertEquals(0.7854079449038646, out.pos[2], 0.0)
    }

    @Test
    fun contextLifeWritable() {
        val src = "this.position.x = this.life; this.life = 40.6; this.position.y = this.life; this.life = -3; this.position.z = this.life;"
        // 标量快路径
        val fast = eval("", src, "", 0, 1, 0)
        assertEquals(-1.0, fast.pos[0], 1e-12)
        assertEquals(41.0, fast.pos[1], 1e-12)
        assertEquals(-1.0, fast.pos[2], 1e-12)
        assertEquals(-1.0, fast.life, 1e-12)
        // AST 解释器（fastMath 关闭标量快路径）
        val ast = eval("", src, "", 0, 1, 0, fastMath = true)
        assertEquals(-1.0, ast.pos[0], 1e-12)
        assertEquals(41.0, ast.pos[1], 1e-12)
        assertEquals(-1.0, ast.pos[2], 1e-12)
        assertEquals(-1.0, ast.life, 1e-12)
    }

    @Test
    fun durationFieldReadableInSetupAndProcess() {
        // setup 读 this.duration（来自 SetupEnv）
        val prog1 = parseProgram("setup { global d = this.duration; } process { this.position.x = d; }")
        val obj1 = ScriptRuntime.createObjectState(0)
        ScriptRuntime.runSetup(prog1, obj1, ScriptRuntime.SetupEnv(10.0, 0.0, emptyMap(), duration = 40.0))
        val out1 = ScriptRuntime.evalProcess(
            prog1, obj1, ScriptRuntime.createStatics(),
            ScriptRuntime.ProcessCtx(0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap(), duration = 40.0),
        )
        assertEquals(40.0, out1.pos[0], 1e-12)

        // process 读 this.duration：标量快路径
        val prog2 = parseProgram("process { this.position.x = this.duration; }")
        val obj2 = ScriptRuntime.createObjectState(0)
        ScriptRuntime.runSetup(prog2, obj2, ScriptRuntime.SetupEnv(1.0, 0.0, emptyMap(), duration = 25.0))
        val fast = ScriptRuntime.evalProcess(
            prog2, obj2, ScriptRuntime.createStatics(),
            ScriptRuntime.ProcessCtx(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap(), duration = 25.0),
        )
        assertEquals(25.0, fast.pos[0], 1e-12)

        // process 读 this.duration：AST 解释器（fastMath=true 关闭标量快路径）
        val ast = ScriptRuntime.evalProcess(
            prog2, obj2, ScriptRuntime.createStatics(),
            ScriptRuntime.ProcessCtx(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap(), fastMath = true, duration = 25.0),
        )
        assertEquals(25.0, ast.pos[0], 1e-12)
    }

    @Test
    fun contextColorAndVec4() {
        val program = parseProgram(
            "process { v = vec4(1,2,3,4); this.position.x = v.x; this.position.y = v.y; this.position.z = v.z; this.color = vec3(0.5, 0.25, 0.125); this.scale = v.w; }",
        )
        val obj = ScriptRuntime.createObjectState(0)
        ScriptRuntime.runSetup(program, obj, ScriptRuntime.SetupEnv(10.0, 0.0, emptyMap()))
        val ctx = ScriptRuntime.ProcessCtx(0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap())
        val out = ScriptRuntime.evalProcess(program, obj, ScriptRuntime.createStatics(), ctx)
        assertEquals(listOf(1.0, 2.0, 3.0), out.pos.toList())
        assertEquals(listOf(0.5, 0.25, 0.125, 1.0), out.color.toList())
        assertEquals(4.0, out.scale, 1e-12)
    }
}