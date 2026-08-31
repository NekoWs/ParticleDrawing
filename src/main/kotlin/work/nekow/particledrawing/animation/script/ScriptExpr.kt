package work.nekow.particledrawing.animation.script

import work.nekow.particledrawing.core.easing.EasingType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * 标量表达式求值（旧 `animation.expr.ExpressionEvaluator` 存活子集）。
 *
 * 旧的通用解释器（向量/矩阵运算、`evalFunctionCode` 代码块执行）已由 script-lang
 * 的 [ScriptRuntime] / [ScriptScalarProgram] 取代，故移除；本文件仅保留仍被
 * [ScalarProgram]（纯标量快路径的 RPN 编译）与 `ClientAnimationProgramManager.setVariable`
 * （变量热更公式求值）使用的标量表达式求值能力。
 */

/** 变量关键帧（tick / 值 / 缓动；`Double` tick，供变量插值使用）。 */
data class Keyframe(val tick: Double, val value: Double, val easing: EasingType)

/** 标量函数签名：名称 -> 参数个数（与编辑器 easing.js 的标量子集对齐）。 */
private val FUNCS = mapOf(
    "sin" to 1, "cos" to 1, "tan" to 1, "asin" to 1, "acos" to 1, "atan" to 1, "atan2" to 2,
    "sqrt" to 1, "abs" to 1, "sign" to 1, "exp" to 1, "log" to 1, "ln" to 1,
    "floor" to 1, "ceil" to 1, "round" to 1, "fract" to 1, "pow" to 2, "min" to 2, "max" to 2,
    "clamp" to 3, "lerp" to 3, "step" to 2, "smoothstep" to 3, "mod" to 2, "random" to 0, "rand" to 1,
)

private val PREC = mapOf('+' to 1, '-' to 1, '*' to 2, '/' to 2, '%' to 2, '^' to 3)
private const val NEG_PREC = 2.5 // 一元负号优先级：高于 * / %，低于 ^（-2^2 = -(2^2)）

/** RPN 编译缓存：表达式字符串 -> 逆波兰序列（tokenize + shunting-yard 结果）。 */
private val rpnCache = ConcurrentHashMap<String, List<Any>>()

/** 标量表达式词法单元。向量/矩阵、分量访问等非标量形态仍会被词法识别（供快路径回退判定）。 */
sealed class ExprToken {
    data class Num(val v: Double) : ExprToken()
    data class Var(val name: String) : ExprToken()
    data class Func(val name: String) : ExprToken()
    data class Comp(val axis: Char) : ExprToken()
    data class Sym(val c: Char) : ExprToken()
    data object Neg : ExprToken()
}

private fun scalar(args: List<Any>, name: String, i: Int): Double =
    args[i] as? Double ?: throw IllegalArgumentException("$name 需要标量值")

private fun evalFunction(name: String, args: List<Any>): Double {
    val num = { i: Int -> scalar(args, name, i) }
    return when (name) {
        "sin" -> sin(num(0))
        "cos" -> cos(num(0))
        "tan" -> tan(num(0))
        "asin" -> asin(num(0))
        "acos" -> acos(num(0))
        "atan" -> atan(num(0))
        "atan2" -> atan2(num(0), num(1))
        "sqrt" -> sqrt(num(0))
        "abs" -> abs(num(0))
        "sign" -> sign(num(0))
        "exp" -> exp(num(0))
        "log" -> ln(num(0))
        "ln" -> ln(num(0))
        "floor" -> floor(num(0))
        "ceil" -> ceil(num(0))
        "round" -> round(num(0))
        "fract" -> num(0) - floor(num(0))
        "pow" -> num(0).pow(num(1))
        "min" -> min(num(0), num(1))
        "max" -> max(num(0), num(1))
        "clamp" -> num(0).coerceIn(num(1), num(2))
        "lerp" -> num(0) + (num(1) - num(0)) * num(2)
        "step" -> if (num(1) >= num(0)) 1.0 else 0.0
        "smoothstep" -> {
            val t = ((num(2) - num(0)) / (num(1) - num(0))).coerceIn(0.0, 1.0)
            t * t * (3 - 2 * t)
        }
        "mod" -> { val a = num(0); val b = num(1); a - b * floor(a / b) }
        "random" -> Math.random()
        "rand" -> { val x = sin(num(0) * 127.1 + 311.7) * 43758.5453; x - floor(x) }
        else -> throw IllegalArgumentException("未知函数: $name")
    }
}

private fun applyOp(op: Char, a: Double, b: Double): Double = when (op) {
    '+' -> a + b
    '-' -> a - b
    '*' -> a * b
    '/' -> a / b
    '%' -> a % b
    '^' -> a.pow(b)
    else -> throw IllegalArgumentException("运算符 $op 类型不匹配")
}

private fun tokenizeExpr(expr: String): List<ExprToken> {
    val tokens = mutableListOf<ExprToken>()
    var i = 0
    var expectOperand = true
    while (i < expr.length) {
        val c = expr[i]
        if (c == ' ' || c == '\t' || c == '\n') { i++; continue }
        if (c == '.' && i + 1 < expr.length && expr[i + 1] in "xyz") {
            tokens.add(ExprToken.Comp(expr[i + 1])); i += 2; expectOperand = false; continue
        }
        if (c.isDigit() || c == '.') {
            var j = i
            while (j < expr.length && (expr[j].isDigit() || expr[j] == '.')) j++
            tokens.add(ExprToken.Num(expr.substring(i, j).toDouble())); i = j; expectOperand = false; continue
        }
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < expr.length && (expr[j].isLetterOrDigit() || expr[j] == '_')) j++
            val name = expr.substring(i, j)
            when {
                name == "pi" -> tokens.add(ExprToken.Num(Math.PI))
                name == "e" -> tokens.add(ExprToken.Num(Math.E))
                FUNCS.containsKey(name) -> tokens.add(ExprToken.Func(name))
                else -> tokens.add(ExprToken.Var(name))
            }
            i = j; expectOperand = false; continue
        }
        if (c == '-' && expectOperand) { tokens.add(ExprToken.Neg); i++; continue } // 一元负号
        if (c in "+-*/%^(),") { tokens.add(ExprToken.Sym(c)); i++; expectOperand = (c == '(' || c == ',' || c in "+-*/%^"); continue }
        i++
    }
    return tokens
}

/** 编译标量表达式为 RPN（tokenize + shunting-yard，变量保留符号），结果按表达式字符串缓存复用。 */
fun compile(expr: String): List<Any> {
    rpnCache[expr]?.let { return it }
    val output = mutableListOf<Any>()
    val stack = mutableListOf<Any>()
    for (tk in tokenizeExpr(expr)) {
        when (tk) {
            is ExprToken.Num -> output.add(tk.v)
            is ExprToken.Var -> output.add(tk)
            is ExprToken.Func -> stack.add(tk.name)
            is ExprToken.Comp -> output.add(tk)
            is ExprToken.Neg -> {
                while (stack.isNotEmpty()) {
                    val top = stack.last()
                    if (top == '(' || top == "neg") break
                    if (top is String && top in FUNCS) { output.add(stack.removeAt(stack.size - 1)); continue }
                    if (top is Char && PREC.containsKey(top) && (PREC[top] ?: 0) > NEG_PREC) output.add(stack.removeAt(stack.size - 1))
                    else break
                }
                stack.add("neg")
            }
            is ExprToken.Sym -> when (tk.c) {
                ',' -> { while (stack.isNotEmpty() && stack.last() != '(') output.add(stack.removeAt(stack.size - 1)) }
                '(' -> stack.add('(')
                ')' -> {
                    while (stack.isNotEmpty() && stack.last() != '(') output.add(stack.removeAt(stack.size - 1))
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    if (stack.isNotEmpty() && stack.last() is String && (stack.last() as String) in FUNCS) {
                        output.add(stack.removeAt(stack.size - 1))
                    }
                }
                else -> {
                    val prec = PREC[tk.c] ?: 0
                    val rightAssoc = tk.c == '^'
                    while (stack.isNotEmpty()) {
                        val top = stack.last()
                        if (top == '(') break
                        if (top == "neg") { if (NEG_PREC > prec) output.add(stack.removeAt(stack.size - 1)) else break; continue }
                        if (top is String && top in FUNCS) { output.add(stack.removeAt(stack.size - 1)); continue }
                        if (top is Char && PREC.containsKey(top)) {
                            val tp = PREC[top] ?: 0
                            if (tp > prec || (tp == prec && !rightAssoc)) output.add(stack.removeAt(stack.size - 1))
                            else break
                        } else break
                    }
                    stack.add(tk.c)
                }
            }
        }
    }
    while (stack.isNotEmpty()) output.add(stack.removeAt(stack.size - 1))
    rpnCache[expr] = output
    return output
}

/** 求值单个标量表达式。vars 为 Map<String, Double>（求值期已解析的变量）。 */
fun evaluate(expr: String, vars: Map<String, Any>): Double {
    val output = compile(expr)
    val s = mutableListOf<Any>()
    for (o in output) {
        when (o) {
            is Double -> s.add(o)
            is ExprToken.Var -> {
                val v = vars[o.name] ?: throw IllegalArgumentException("未知变量: ${o.name}")
                s.add(v)
            }
            "neg" -> {
                val v = s.removeAt(s.size - 1) as? Double ?: throw IllegalArgumentException("一元负号类型不支持")
                s.add(-v)
            }
            is ExprToken.Comp -> throw IllegalArgumentException("分量访问需要向量")
            is String if o in FUNCS -> {
                val n = FUNCS[o]!!
                val args = (0 until n).map { s.removeAt(s.size - 1) }.reversed()
                s.add(evalFunction(o, args))
            }
            is Char -> {
                val b = s.removeAt(s.size - 1) as? Double ?: throw IllegalArgumentException("运算符类型不匹配")
                val a = s.removeAt(s.size - 1) as? Double ?: throw IllegalArgumentException("运算符类型不匹配")
                s.add(applyOp(o, a, b))
            }
            else -> throw IllegalArgumentException("意外 token: $o")
        }
    }
    return s.last() as? Double ?: throw IllegalArgumentException("表达式求值结果不是标量")
}

/** 变量关键帧插值（段 i→i+1 用后一关键帧的缓动）。 */
fun varKfValue(kf: List<Keyframe>, t: Double): Double {
    if (kf.isEmpty()) return 0.0
    if (t <= kf[0].tick) return kf[0].value
    if (t >= kf.last().tick) return kf.last().value
    for (i in 0 until kf.size - 1) {
        val a = kf[i]; val b = kf[i + 1]
        if (t >= a.tick && t <= b.tick) {
            val dur = b.tick - a.tick
            val f = if (dur == 0.0) 1.0 else (t - a.tick) / dur
            val e = b.easing.evaluate(f.toFloat()).toDouble()
            return a.value + (b.value - a.value) * e
        }
    }
    return kf.last().value
}
