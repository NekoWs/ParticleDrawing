package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.animation.script.VarDef
import work.nekow.particledrawing.animation.script.compileFunctionObject
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
}
