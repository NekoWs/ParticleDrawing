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
        val out = eval("", "Context.position = [Context.index*2, Context.index+1, Context.count]; Context.color.r = Context.index/Context.count; Context.scale = 0.5; Context.velocity.x = Context.index; Context.glow = 1; Context.light = 12;", "", 0, 3, 0)
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
            "Context.position.x = arr[0]; Context.position.y = arr.find(2); Context.position.z = arr.includes(9) ? 1 : 0; Context.scale = brr.size();",
            "", 0, 1, 0,
        )
        assertEquals(listOf(3.0, 2.0, 0.0), out.pos.toList())
        assertEquals(3.0, out.scale, 1e-12)
    }

    @Test
    fun controlFlow() {
        val out = eval(
            "",
            "s = 0; for (k = 0; k < 5; k = k + 1) { s = s + k; } while (s < 11) { s = s + 1; } if (s > 10) { Context.position.x = s; } else { Context.position.x = -1; } Context.position.y = (s == 12) ? 2 : 0;",
            "", 0, 1, 0,
        )
        assertEquals(listOf(11.0, 0.0, 0.0), out.pos.toList())
    }

    @Test
    fun funcRecursion() {
        val out = eval(
            "",
            "Context.position.x = fib(6); Context.position.y = fac(4);",
            "func fib(nn) { if (nn < 2) { return nn; } return fib(nn-1) + fib(nn-2); }\nfunc fac(nn) { if (nn <= 1) { return 1; } return nn * fac(nn-1); }",
            0, 1, 0,
        )
        assertEquals(listOf(8.0, 24.0, 0.0), out.pos.toList())
    }

    @Test
    fun vecMat() {
        val out = eval(
            "",
            "v = vec(1,2,3); m = rotZ(pi/2); w = m * v; Context.position = w; Context.color = [len(w)/4, dot(v,w)/12, cross(v,w).y/10, 1];",
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
        val out0 = eval("", "Context.position.x = noise(Context.index, 0.5, 1.5) * 10; Context.position.y = rand() * 10; Context.position.z = rand(7) * 10;", "", 42, 2, 0)
        assertEquals(6.146115226337443, out0.pos[0], 1e-12)
        assertEquals(6.011037519201636, out0.pos[1], 1e-12)
        assertEquals(0.11704753153026104, out0.pos[2], 1e-12)

        val out1 = eval("", "Context.position.x = noise(Context.index, 0.5, 1.5) * 10; Context.position.y = rand() * 10; Context.position.z = rand(7) * 10;", "", 42, 2, 1)
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
        val program = parseProgram("setup { global position = 99; global index = 88; } process { Context.position = [5, 5, 5]; Context.position.x = 5; Context.position.y = Context.position.x; Context.position.z = Context.index; }")
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
        val program = parseProgram("process { sin = 3; Context.position.x = sin; Context.position.y = sin(1); }")
        val obj = ScriptRuntime.createObjectState(0)
        ScriptRuntime.runSetup(program, obj, ScriptRuntime.SetupEnv(10.0, 0.0, emptyMap()))
        val ctx = ScriptRuntime.ProcessCtx(0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap())
        val out = ScriptRuntime.evalProcess(program, obj, ScriptRuntime.createStatics(), ctx)
        assertEquals(3.0, out.pos[0], 1e-12)
        assertEquals(kotlin.math.sin(1.0), out.pos[1], 1e-12)
    }

    @Test
    fun fastMathUsesFastBuiltinsInProcess() {
        val program = parseProgram("process { Context.position.x = sin(0.5); Context.position.y = exp(1); Context.position.z = atan(1); }")
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
        val src = "Context.position.x = Context.life; Context.life = 40.6; Context.position.y = Context.life; Context.life = -3; Context.position.z = Context.life;"
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
    fun contextColorAndVec4() {
        val program = parseProgram(
            "process { v = vec4(1,2,3,4); Context.position.x = v.x; Context.position.y = v.y; Context.position.z = v.z; Context.color = vec3(0.5, 0.25, 0.125); Context.scale = v.w; }",
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