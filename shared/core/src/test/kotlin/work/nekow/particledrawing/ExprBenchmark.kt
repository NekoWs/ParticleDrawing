package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.animation.script.VarDef
import work.nekow.particledrawing.animation.script.compileFunctionObject
import work.nekow.particledrawing.animation.script.evaluate
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 含向量/矩阵/分量访问的代码块：快路径编译必须返回 null，交给调用方回退解释器。 */
private val NON_SCALAR_BLOCKS = listOf(
    "vec 拆包" to "[x,y,z] = vec(1,2,3);",
    "分量访问" to "x = v.x;\ny = v.y;",
    "dot" to "x = dot(vec(1,0,0), vec(0,1,0));",
    "rotX" to "m = rotX(t);\n[x,y,z] = [0,0,0];",
)

/**
 * 纯 JVM 基准：模拟游戏内函数对象（派生粒子）求值并计时。
 *
 * 求值结果的一致性归 [ExprBenchmarkConsistencyTest]：同一条公式分别走快路径与解释器再逐槽比对。
 */
fun main() {
    data class Preset(val name: String, val vars: List<Pair<String, String>>, val code: String)

    val presets = listOf(
        Preset(
            "sphere",
            listOf("rad" to "3"),
            "th = acos(1-2*(i+0.5)/n);\nph = i*PI*(3-sqrt(5));\n[x,y,z] = [rad*sin(th)*cos(ph), rad*cos(th), rad*sin(th)*sin(ph)];\n[r,g,b,a] = [1,1,1,1];\nglow = 1;\nlight = 12",
        ),
        Preset(
            "cube",
            listOf("edge" to "4", "sx" to "8", "sy" to "8", "sz" to "8"),
            "[x,y,z] = [((floor(i/(sy*sz)))/(sx-1)-0.5)*edge, ((floor((i%(sy*sz))/sz))/(sy-1)-0.5)*edge, ((i%sz)/(sz-1)-0.5)*edge];\n[r,g,b,a] = [1,1,1,1];\nglow = 0;\nlight = 0",
        ),
        Preset(
            "torus",
            listOf("major" to "3", "minor" to "1", "m" to "24", "k" to "12"),
            "th = i%k/k*2*PI;\nph = floor(i/k)/m*2*PI;\n[x,y,z] = [(major+minor*cos(th))*cos(ph), minor*sin(th), (major+minor*cos(th))*sin(ph)];\n[r,g,b,a] = [1,1,1,1];\nglow = 1;\nlight = 10",
        ),
        Preset(
            "star",
            listOf("rad" to "20"),
            "m = floor(pow(n, 0.5));\nu = floor(i / m) * 2 * PI / m;\nv = i % m * PI / m - PI / 2;\nx = rad * pow(cos(u) * cos(v), 3);\ny = rad * pow(sin(u) * cos(v), 3);\nz = rad * pow(sin(v), 3)",
        ),
    )

    val count = 50_000
    val n = count.toDouble()
    val t = 0.0

    var total = 0.0
    for (p in presets) {
        val varDefs = p.vars.map { (name, expr) -> VarDef(name, expr.toDouble(), emptyList()) }
        val cf = compileFunctionObject(p.code, varDefs) ?: run {
            continue
        }
        val regs = cf.allocRegs()
        val stack = cf.allocStack()
        var sink = 0.0
        for (i in 0 until count) cf.eval(i.toDouble(), n, t, regs, stack)
        val ms = measureNanoTime {
            for (i in 0 until count) {
                cf.eval(i.toDouble(), n, t, regs, stack)
                sink += regs[Reg.X]
            }
        } / 1e6
        total += ms
        println("%-8s %6.2f ms   (sink=%.1f)".format(p.name, ms, sink))
    }
    println("快路径合计: %.2f ms".format(total))

    // 含向量/矩阵的代码块应返回 null
    println()
    println("=== 非纯标量代码块回退验证 ===")
    for ((label, code) in NON_SCALAR_BLOCKS) {
        val cf = compileFunctionObject(code, emptyList())
        println("%-12s -> %s".format(label, if (cf == null) "回退（正确）" else "误判为快路径（错误）"))
        check(cf == null) { "$label 应回退解释器，却被编进了快路径" }
    }
}

/**
 * 快路径（[compileFunctionObject] 编出的标量字节码）与解释器（[evaluate]）的求值一致性。
 *
 * 同一条公式两条路径各求一次，逐属性槽比对；含向量/分量的代码块必须判定为回退，不能编进快路径。
 */
class ExprBenchmarkConsistencyTest {

    private class Formula(
        val label: String,
        val vars: List<Pair<String, Double>>,
        val code: String,
        /** 参与比对的输出槽名，必须都能在属性寄存器里找到。 */
        val outputs: List<String>,
    )

    /** 公式里的输出名 -> 属性寄存器下标，与 ScalarProgram 的 ATTR_SLOTS 同一张表。 */
    private val attrSlots = mapOf(
        "x" to Reg.X, "y" to Reg.Y, "z" to Reg.Z,
        "r" to Reg.R, "g" to Reg.G, "b" to Reg.B, "a" to Reg.A,
        "vx" to Reg.VX, "vy" to Reg.VY, "vz" to Reg.VZ,
        "sc" to Reg.SC, "glow" to Reg.GLOW, "light" to Reg.LIGHT,
    )

    private val sampleI = listOf(0.0, 0.5, 1.0, 7.0, 63.5, 64.0, 200.0)
    private val sampleT = listOf(0.0, 1234.5)
    private val sampleN = 256.0

    @Test
    fun `同一公式快路径与解释器逐槽结果相同`() {
        val formulas = listOf(
            Formula(
                "球面（变量 + 中间量）", listOf("rad" to 3.0),
                """
                th = acos(1-2*(i+0.5)/n);
                ph = i*PI*(3-sqrt(5));
                x = rad*sin(th)*cos(ph);
                y = rad*cos(th);
                z = rad*sin(th)*sin(ph);
                sc = 1 + 0.5*rand(i);
                """,
                listOf("x", "y", "z", "sc"),
            ),
            Formula(
                "标量函数表", emptyList(),
                """
                r = clamp(i/n, 0, 1);
                g = lerp(0, 1, i/n);
                b = smoothstep(0, n, i);
                a = 1 - fract(i/n);
                vx = pow(i, 0.5) * sign(i - n/2);
                vy = mod(i, 7) - 3;
                vz = min(i, n-i) + max(-i, 0);
                glow = step(0.5, fract(i*0.25));
                light = floor(abs(sin(i)) * 16);
                """,
                listOf("r", "g", "b", "a", "vx", "vy", "vz", "glow", "light"),
            ),
        )
        for (f in formulas) {
            val varDefs = f.vars.map { (name, value) -> VarDef(name, value, emptyList()) }
            val cf = assertNotNull(compileFunctionObject(f.code, varDefs), "${f.label}：纯标量公式应能编进快路径")
            val regs = cf.allocRegs()
            val stack = cf.allocStack()
            for (i in sampleI) {
                for (t in sampleT) {
                    cf.eval(i, sampleN, t, regs, stack)
                    val scope = interpreterScope(f, i, t)
                    for (name in f.outputs) {
                        val slot = attrSlots[name] ?: error("${f.label}：$name 不是属性槽")
                        val want = assertNotNull(scope[name], "${f.label} i=$i t=$t：解释器没算出 $name")
                        assertEquals(want, regs[slot], 0.0, "${f.label} i=$i t=$t 槽 $name")
                    }
                }
            }
        }
    }

    @Test
    fun `含向量或分量的代码块不编进快路径`() {
        for ((label, code) in NON_SCALAR_BLOCKS) {
            assertNull(compileFunctionObject(code, emptyList()), "$label 应回退解释器，不可编进快路径")
        }
    }

    /** 用解释器按书写顺序逐条求值，返回公式里每个名字的结果；公式只允许单名赋值。 */
    private fun interpreterScope(f: Formula, i: Double, t: Double): Map<String, Double> {
        val scope = HashMap<String, Double>()
        scope["i"] = i
        scope["n"] = sampleN
        scope["t"] = t
        for ((name, value) in f.vars) scope[name] = value
        for (raw in f.code.split(';')) {
            val stmt = raw.trim()
            if (stmt.isEmpty()) continue
            val eq = stmt.indexOf('=')
            scope[stmt.substring(0, eq).trim()] = evaluate(stmt.substring(eq + 1).trim(), scope)
        }
        return scope
    }
}
