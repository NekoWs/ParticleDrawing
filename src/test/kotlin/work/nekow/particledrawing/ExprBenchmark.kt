package work.nekow.particledrawing

import work.nekow.particledrawing.animation.expr.EvalResult
import work.nekow.particledrawing.animation.expr.ExpressionEvaluator
import work.nekow.particledrawing.animation.expr.Reg
import work.nekow.particledrawing.animation.expr.VarDef
import work.nekow.particledrawing.animation.expr.compileFunctionObject
import kotlin.math.abs
import kotlin.system.measureNanoTime

/**
 * 纯 JVM 基准：模拟游戏内函数对象（派生粒子）求值。
 * 含性能计时、快/慢路径结果一致性对比、非纯标量代码块回退验证。
 */
fun main() {
    data class Preset(val name: String, val vars: List<Pair<String, String>>, val code: String)

    val presets = listOf(
        Preset(
            "sphere",
            listOf("rad" to "3"),
            "th = acos(1-2*(i+0.5)/n);\nph = i*pi*(3-sqrt(5));\n[x,y,z] = [rad*sin(th)*cos(ph), rad*cos(th), rad*sin(th)*sin(ph)];\n[r,g,b,a] = [1,1,1,1];\nglow = 1;\nlight = 12",
        ),
        Preset(
            "cube",
            listOf("edge" to "4", "sx" to "8", "sy" to "8", "sz" to "8"),
            "[x,y,z] = [((floor(i/(sy*sz)))/(sx-1)-0.5)*edge, ((floor((i%(sy*sz))/sz))/(sy-1)-0.5)*edge, ((i%sz)/(sz-1)-0.5)*edge];\n[r,g,b,a] = [1,1,1,1];\nglow = 0;\nlight = 0",
        ),
        Preset(
            "torus",
            listOf("major" to "3", "minor" to "1", "m" to "24", "k" to "12"),
            "th = i%k/k*2*pi;\nph = floor(i/k)/m*2*pi;\n[x,y,z] = [(major+minor*cos(th))*cos(ph), minor*sin(th), (major+minor*cos(th))*sin(ph)];\n[r,g,b,a] = [1,1,1,1];\nglow = 1;\nlight = 10",
        ),
        Preset(
            "star",
            listOf("rad" to "20"),
            "m = floor(pow(n, 0.5));\nu = floor(i / m) * 2 * pi / m;\nv = i % m * pi / m - pi / 2;\nx = rad * pow(cos(u) * cos(v), 3);\ny = rad * pow(sin(u) * cos(v), 3);\nz = rad * pow(sin(v), 3)",
        ),
    )

    val count = 50_000
    val n = count.toDouble()
    val t = 0.0

    // ---- 性能：快路径 5w ----
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

    // ---- 正确性：快/慢路径一致（小规模） ----
    println()
    var ok = true
    for (p in presets) {
        val varDefs = p.vars.map { (name, expr) -> VarDef(name, expr.toDouble(), emptyList()) }
        val cf = compileFunctionObject(p.code, varDefs) ?: run { println("${p.name}: 回退，跳过"); continue }
        val regs = cf.allocRegs()
        val stack = cf.allocStack()
        val vars = p.vars.toMap()
        for (i in 0 until 100) {
            cf.eval(i.toDouble(), 100.0, t, regs, stack)
            val slow = evalSlow(p.code, vars, i, 100, t)
            val mismatch = abs(regs[Reg.X] - slow.pos.x) > 1e-9 ||
                    abs(regs[Reg.Y] - slow.pos.y) > 1e-9 ||
                    abs(regs[Reg.Z] - slow.pos.z) > 1e-9 ||
                    abs(regs[Reg.R] - slow.color[0]) > 1e-9 ||
                    abs(regs[Reg.SC] - slow.scale) > 1e-9 ||
                    abs(regs[Reg.LIGHT] - slow.light) > 1e-9 ||
                    (regs[Reg.GLOW] > 0.5) != slow.glow
            if (mismatch) {
                ok = false
                println("${p.name} 不一致 i=$i  fast=(${regs[Reg.X]},${regs[Reg.Y]},${regs[Reg.Z]}) slow=(${slow.pos.x},${slow.pos.y},${slow.pos.z})")
                break
            }
        }
    }
    println(if (ok) "一致性: PASS" else "一致性: FAIL")

    // ---- 回退验证：含向量/矩阵的代码块应返回 null ----
    println()
    println("=== 非纯标量代码块回退验证 ===")
    val nonScalar = listOf(
        "vec 拆包" to "[x,y,z] = vec(1,2,3);",
        "分量访问" to "x = v.x;\ny = v.y;",
        "dot" to "x = dot(vec(1,0,0), vec(0,1,0));",
        "rotX" to "m = rotX(t);\n[x,y,z] = [0,0,0];",
    )
    for ((label, code) in nonScalar) {
        val cf = compileFunctionObject(code, emptyList())
        println("%-12s -> %s".format(label, if (cf == null) "回退（正确）" else "误判为快路径（错误）"))
    }

    // ---- 慢路径（优化前）1w 对比 ----
    println()
    println("=== 通用解释器（优化前，sphere/cube 各 1w 做对比） ===")
    for (p in presets.take(2)) {
        val vars = p.vars.toMap()
        var sink = 0.0
        for (i in 0 until 10_000) sink += evalSlow(p.code, vars, i, 10_000, t).pos.x
        val ms = measureNanoTime {
            for (i in 0 until 10_000) sink += evalSlow(p.code, vars, i, 10_000, t).pos.x
        } / 1e6
        println("%-8s %.2f ms (1w 粒子, sink=%.1f)".format(p.name, ms, sink))
    }
}

/** 与 ClientAnimationPlayer 原逻辑等价的慢路径：buildEnv + evalFunctionCode。 */
private fun evalSlow(code: String, vars: Map<String, String>, i: Int, n: Int, t: Double): EvalResult {
    val env = HashMap<String, Any>()
    env["i"] = i.toDouble(); env["n"] = n.toDouble(); env["t"] = t
    val memo = HashMap<String, Any>()
    val inStack = HashSet<String>()
    fun resolve(name: String): Any {
        memo[name]?.let { return it }
        env[name]?.let { return it }
        val expr = vars[name] ?: throw IllegalArgumentException("未知变量: $name")
        if (name in inStack) throw IllegalArgumentException("循环引用: $name")
        inStack.add(name)
        val resolver = object : java.util.AbstractMap<String, Any>() {
            override val entries: MutableSet<MutableMap.MutableEntry<String, Any>> get() = mutableSetOf()
            override fun get(key: String): Any {
                memo[key]?.let { return it }
                env[key]?.let { return it }
                return resolve(key)
            }
        }
        val v = ExpressionEvaluator.evaluate(expr, resolver)
        inStack.remove(name)
        memo[name] = v
        return v
    }
    for (name in vars.keys) resolve(name)
    for ((k, v) in memo) env[k] = v
    return ExpressionEvaluator.evalFunctionCode(code, env)
}
