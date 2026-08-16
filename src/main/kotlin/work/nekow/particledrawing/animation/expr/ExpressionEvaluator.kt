package work.nekow.particledrawing.animation.expr

import work.nekow.particledrawing.core.easing.EasingType
import kotlin.math.*

/**
 * 表达式求值器：标量 / 向量 vec3 / 矩阵 mat3。
 *
 * 与网页编辑器 easing.js 等价：支持函数、四则运算、向量/矩阵运算、
 * [x,y,z]=向量 拆包、分号代码块、属性保留字、临时变量。
 */

/** 三维向量。 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    fun hadamard(o: Vec3) = Vec3(x * o.x, y * o.y, z * o.z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l == 0.0) Vec3(0.0, 0.0, 0.0) else Vec3(x / l, y / l, z / l)
    }
    fun component(axis: Char): Double = when (axis) {
        'x' -> x; 'y' -> y; 'z' -> z
        else -> throw IllegalArgumentException("未知分量: $axis")
    }
}

/** 3x3 矩阵（行主序）。 */
data class Mat3(val m: Array<DoubleArray>) {
    fun times(o: Vec3): Vec3 = Vec3(
        m[0][0] * o.x + m[0][1] * o.y + m[0][2] * o.z,
        m[1][0] * o.x + m[1][1] * o.y + m[1][2] * o.z,
        m[2][0] * o.x + m[2][1] * o.y + m[2][2] * o.z,
    )

    fun times(o: Mat3): Mat3 {
        val r = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) for (k in 0..2) r[i][j] += m[i][k] * o.m[k][j]
        return Mat3(r)
    }

    companion object {
        fun rotX(t: Double): Mat3 {
            val c = cos(t); val s = sin(t)
            return Mat3(arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, c, -s), doubleArrayOf(0.0, s, c)))
        }
        fun rotY(t: Double): Mat3 {
            val c = cos(t); val s = sin(t)
            return Mat3(arrayOf(doubleArrayOf(c, 0.0, s), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-s, 0.0, c)))
        }
        fun rotZ(t: Double): Mat3 {
            val c = cos(t); val s = sin(t)
            return Mat3(arrayOf(doubleArrayOf(c, -s, 0.0), doubleArrayOf(s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0)))
        }
        fun rotAxis(axis: Vec3, t: Double): Mat3 {
            val l = axis.length().let { if (it == 0.0) 1.0 else it }
            val x = axis.x / l; val y = axis.y / l; val z = axis.z / l
            val c = cos(t); val s = sin(t); val C = 1.0 - c
            return Mat3(arrayOf(
                doubleArrayOf(c + x * x * C, x * y * C - z * s, x * z * C + y * s),
                doubleArrayOf(y * x * C + z * s, c + y * y * C, y * z * C - x * s),
                doubleArrayOf(z * x * C - y * s, z * y * C + x * s, c + z * z * C),
            ))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Mat3

        return m.contentDeepEquals(other.m)
    }

    override fun hashCode(): Int {
        return m.contentDeepHashCode()
    }
}

/** 函数签名：名称 -> 参数个数。 */
private val FUNCS = mapOf(
    "sin" to 1, "cos" to 1, "tan" to 1, "asin" to 1, "acos" to 1, "atan" to 1, "atan2" to 2,
    "sqrt" to 1, "abs" to 1, "exp" to 1, "log" to 1, "ln" to 1,
    "floor" to 1, "ceil" to 1, "round" to 1, "pow" to 2, "min" to 2, "max" to 2, "clamp" to 3, "lerp" to 3,
    "vec" to 3, "dot" to 2, "cross" to 2, "len" to 1, "norm" to 1,
    "rotX" to 1, "rotY" to 1, "rotZ" to 1, "rotAxis" to 2,
    "polar" to 2, "sphere" to 3, "torus" to 4,
)

/** 属性保留字（vars 与临时变量不可同名）。 */
val ATTR_NAMES = listOf("x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light")

private val PREC = mapOf('+' to 1, '-' to 1, '*' to 2, '/' to 2, '%' to 2, '^' to 3)
private const val NEG_PREC = 2.5 // 一元负号优先级：高于 * / %，低于 ^（-2^2 = -(2^2)）

/** 函数求值结果。 */
class EvalResult(var pos: Vec3, val color: DoubleArray, var vel: Vec3, var scale: Double, var glow: Boolean, var light: Double)

/** 变量关键帧（tick / 值 / 缓动）。 */
data class Keyframe(val tick: Double, val value: Double, val easing: EasingType)

object ExpressionEvaluator {

    private fun scalar(v: Any, name: String): Double = when (v) {
        is Double -> v
        else -> throw IllegalArgumentException("属性 $name 需要标量值")
    }

    private fun assignAttr(name: String, v: Any, out: EvalResult, scope: MutableMap<String, Any>): Boolean {
        val s = scalar(v, name)
        when (name) {
            "x" -> { out.pos = Vec3(s, out.pos.y, out.pos.z); scope["x"] = s }
            "y" -> { out.pos = Vec3(out.pos.x, s, out.pos.z); scope["y"] = s }
            "z" -> { out.pos = Vec3(out.pos.x, out.pos.y, s); scope["z"] = s }
            "r" -> { out.color[0] = s; scope["r"] = s }
            "g" -> { out.color[1] = s; scope["g"] = s }
            "b" -> { out.color[2] = s; scope["b"] = s }
            "a" -> { out.color[3] = s; scope["a"] = s }
            "vx" -> { out.vel = Vec3(s, out.vel.y, out.vel.z); scope["vx"] = s }
            "vy" -> { out.vel = Vec3(out.vel.x, s, out.vel.z); scope["vy"] = s }
            "vz" -> { out.vel = Vec3(out.vel.x, out.vel.y, s); scope["vz"] = s }
            "sc" -> { out.scale = s; scope["sc"] = s }
            "glow" -> { out.glow = s > 0.5; scope["glow"] = s }
            "light" -> { out.light = s; scope["light"] = s }
            else -> return false
        }
        return true
    }

    private fun evalFunction(name: String, args: List<Any>): Any {
        val num = { i: Int -> scalar(args[i], name) }
        val vec = { i: Int -> args[i] as? Vec3 ?: throw IllegalArgumentException("$name 需要向量") }
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
            "exp" -> exp(num(0))
            "log" -> ln(num(0))
            "ln" -> ln(num(0))
            "floor" -> floor(num(0))
            "ceil" -> ceil(num(0))
            "round" -> round(num(0))
            "pow" -> num(0).pow(num(1))
            "min" -> min(num(0), num(1))
            "max" -> max(num(0), num(1))
            "clamp" -> num(0).coerceIn(num(1), num(2))
            "lerp" -> num(0) + (num(1) - num(0)) * num(2)
            "vec" -> Vec3(num(0), num(1), num(2))
            "dot" -> vec(0).dot(vec(1))
            "cross" -> vec(0).cross(vec(1))
            "len" -> { val v = args[0]; if (v is Vec3) v.length() else abs(scalar(v, "len")) }
            "norm" -> vec(0).normalized()
            "rotX" -> Mat3.rotX(num(0))
            "rotY" -> Mat3.rotY(num(0))
            "rotZ" -> Mat3.rotZ(num(0))
            "rotAxis" -> Mat3.rotAxis(vec(0), num(1))
            "polar" -> Vec3(num(0) * cos(num(1)), 0.0, num(0) * sin(num(1)))
            "sphere" -> Vec3(num(0) * sin(num(1)) * cos(num(2)), num(0) * cos(num(1)), num(0) * sin(num(1)) * sin(num(2)))
            "torus" -> Vec3(
                (num(0) + num(1) * cos(num(2))) * cos(num(3)),
                num(1) * sin(num(2)),
                (num(0) + num(1) * cos(num(2))) * sin(num(3)),
            )
            else -> throw IllegalArgumentException("未知函数: $name")
        }
    }

    private fun applyOp(op: Char, a: Any, b: Any): Any {
        if (op == '+') {
            if (a is Vec3 && b is Vec3) return a + b
            if (a is Double && b is Double) return a + b
        }
        if (op == '-') {
            if (a is Vec3 && b is Vec3) return a - b
            if (a is Double && b is Double) return a - b
        }
        if (op == '*') {
            if (a is Mat3) {
                if (b is Vec3) return a.times(b)
                if (b is Mat3) return a.times(b)
                if (b is Double) return Mat3(a.m.map { r -> r.map { it * b }.toDoubleArray() }.toTypedArray())
            }
            if (a is Vec3) {
                if (b is Double) return a * b
                if (b is Vec3) return a.hadamard(b)
            }
            if (a is Double) {
                if (b is Vec3) return b * a
                if (b is Mat3) return Mat3(b.m.map { r -> r.map { it * a }.toDoubleArray() }.toTypedArray())
                if (b is Double) return a * b
            }
        }
        if (op == '/') {
            if (a is Vec3 && b is Double) return a / b
            if (a is Double && b is Double) return a / b
        }
        if (op == '%') {
            if (a is Double && b is Double) return a % b
        }
        if (op == '^') {
            if (a is Double && b is Double) return a.pow(b)
        }
        throw IllegalArgumentException("运算符 $op 类型不匹配")
    }

    private fun negate(v: Any): Any = when (v) {
        is Double -> -v
        is Vec3 -> Vec3(-v.x, -v.y, -v.z)
        is Mat3 -> Mat3(Array(3) { i -> DoubleArray(3) { j -> -v.m[i][j] } })
        else -> throw IllegalArgumentException("一元负号类型不支持")
    }

    private sealed class Token {
        data class Num(val v: Double) : Token()
        data class Var(val name: String) : Token()
        data class Func(val name: String) : Token()
        data class Comp(val axis: Char) : Token()
        data class Sym(val c: Char) : Token()
        data object Neg : Token()
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var expectOperand = true
        while (i < expr.length) {
            val c = expr[i]
            if (c == ' ' || c == '\t' || c == '\n') { i++; continue }
            if (c == '.' && i + 1 < expr.length && expr[i + 1] in "xyz") {
                tokens.add(Token.Comp(expr[i + 1])); i += 2; expectOperand = false; continue
            }
            if (c.isDigit() || c == '.') {
                var j = i
                while (j < expr.length && (expr[j].isDigit() || expr[j] == '.')) j++
                tokens.add(Token.Num(expr.substring(i, j).toDouble())); i = j; expectOperand = false; continue
            }
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < expr.length && (expr[j].isLetterOrDigit() || expr[j] == '_')) j++
                val name = expr.substring(i, j)
                when {
                    name == "pi" -> tokens.add(Token.Num(Math.PI))
                    name == "e" -> tokens.add(Token.Num(Math.E))
                    FUNCS.containsKey(name) -> tokens.add(Token.Func(name))
                    else -> tokens.add(Token.Var(name))
                }
                i = j; expectOperand = false; continue
            }
            if (c == '-' && expectOperand) { tokens.add(Token.Neg); i++; continue } // 一元负号
            if (c in "+-*/%^(),") { tokens.add(Token.Sym(c)); i++; expectOperand = (c == '(' || c == ',' || c in "+-*/%^"); continue }
            i++
        }
        return tokens
    }

    /** 求值单个表达式。vars 为 Map<String, Double>（求值期已解析的变量）。 */
    fun evaluate(expr: String, vars: Map<String, Any>): Any {
        val output = mutableListOf<Any>()
        val stack = mutableListOf<Any>()
        for (tk in tokenize(expr)) {
            when (tk) {
                is Token.Num -> output.add(tk.v)
                is Token.Var -> {
                    val v = vars[tk.name] ?: throw IllegalArgumentException("未知变量: ${tk.name}")
                    output.add(v)
                }
                is Token.Func -> stack.add(tk.name)
                is Token.Comp -> output.add(tk)
                is Token.Neg -> {
                    while (stack.isNotEmpty()) {
                        val top = stack.last()
                        if (top == '(' || top == "neg") break
                        if (top is String && top in FUNCS) { output.add(stack.removeAt(stack.size - 1)); continue }
                        if (top is Char && PREC.containsKey(top) && (PREC[top] ?: 0) > NEG_PREC) output.add(stack.removeAt(stack.size - 1))
                        else break
                    }
                    stack.add("neg")
                }
                is Token.Sym -> when (tk.c) {
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

        val s = mutableListOf<Any>()
        for (o in output) {
            when (o) {
                is Double, is Vec3, is Mat3 -> s.add(o)
                "neg" -> {
                    val v = s.removeAt(s.size - 1)
                    s.add(negate(v))
                }
                is Token.Comp -> {
                    val v = s.removeAt(s.size - 1) as? Vec3 ?: throw IllegalArgumentException("分量访问需要向量")
                    s.add(v.component(o.axis))
                }

                is String if o in FUNCS -> {
                    val n = FUNCS[o]!!
                    val args = (0 until n).map { s.removeAt(s.size - 1) }.reversed()
                    s.add(evalFunction(o, args))
                }

                is Char -> {
                    val b = s.removeAt(s.size - 1)
                    val a = s.removeAt(s.size - 1)
                    s.add(applyOp(o, a, b))
                }
            }
        }
        return s.last()
    }

    private fun parseNameList(s: String): List<String> =
        s.trim().removePrefix("[").removeSuffix("]").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseExprList(s: String): List<String> {
        val inner = s.trim()
        if (!inner.startsWith("[") || !inner.endsWith("]")) return listOf(inner)
        val body = inner.substring(1, inner.length - 1)
        val parts = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in body) {
            if (c == '(') depth++
            else if (c == ')') depth--
            if (c == ',' && depth == 0) { parts.add(cur.toString().trim()); cur.setLength(0) }
            else cur.append(c)
        }
        if (cur.isNotEmpty()) parts.add(cur.toString().trim())
        return parts
    }

    /** 执行公式代码块（分号分隔、顺序执行、打包/单分量赋值、临时变量）。 */
    fun evalFunctionCode(code: String, env: Map<String, Any>): EvalResult {
        val out = EvalResult(Vec3(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0, 1.0), Vec3(0.0, 0.0, 0.0), 1.0, false, 0.0)
        val scope = HashMap(env)
        for (stmt in code.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = stmt.indexOf('=')
            if (eq < 0) throw IllegalArgumentException("表达式缺少 = : $stmt")
            val lhs = stmt.substring(0, eq).trim()
            val rhs = stmt.substring(eq + 1).trim()
            if (lhs.startsWith("[")) {
                val names = parseNameList(lhs)
                if (rhs.startsWith("[")) {
                    val exprs = parseExprList(rhs)
                    if (names.size != exprs.size) throw IllegalArgumentException("赋值数量不匹配: $stmt")
                    for (k in names.indices) {
                        val v = evaluate(exprs[k], scope)
                        if (!assignAttr(names[k], v, out, scope)) scope[names[k]] = v
                    }
                } else {
                    val v = evaluate(rhs, scope)
                    if (v is Vec3 && names.size == 3) {
                        val comps = listOf(v.x, v.y, v.z)
                        for (k in 0..2) if (!assignAttr(names[k], comps[k], out, scope)) scope[names[k]] = comps[k]
                    } else if (names.size == 1) {
                        if (!assignAttr(names[0], v, out, scope)) scope[names[0]] = v
                    } else {
                        throw IllegalArgumentException("赋值数量不匹配: $stmt")
                    }
                }
            } else {
                val v = evaluate(rhs, scope)
                if (!assignAttr(lhs, v, out, scope)) scope[lhs] = v
            }
        }
        return out
    }

    /** 变量关键帧插值（b[2] 语义：段 i→i+1 用后一关键帧的缓动）。 */
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
}
