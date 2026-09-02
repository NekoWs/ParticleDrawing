package work.nekow.particledrawing.animation.script

import kotlin.math.ceil
import kotlin.math.floor

/** 脚本运行时错误 / 解析错误。消息会按 JS 端习惯附加行列号。 */
class ScriptException(message: String, val line: Int? = null, val col: Int? = null) : RuntimeException(
    if (line != null && col != null) "$message (line $line, col $col)" else message
)

data class Vec2(val x: Double, val y: Double)

data class Vec3(val x: Double, val y: Double, val z: Double)

data class Vec4(val x: Double, val y: Double, val z: Double, val w: Double)

/** 3x3 行主序矩阵，元素为 3 个长度为 3 的行。 */
data class Mat3(val m: List<List<Double>>)

/** 4x4 行主序矩阵，元素为 4 个长度为 4 的行。 */
data class Mat4(val m: List<List<Double>>)

/** 用户函数值（闭包按顶层函数名查找）。 */
data class FuncVal(val name: String)

// ---- 值类型判定 ----

fun isNum(v: Any?): Boolean = v is Double
fun isBool(v: Any?): Boolean = v is Boolean
fun isString(v: Any?): Boolean = v is String
fun isVec(v: Any?): Boolean = v is Vec2 || v is Vec3 || v is Vec4
fun isMat(v: Any?): Boolean = v is Mat3 || v is Mat4
fun isArray(v: Any?): Boolean = v is MutableList<*>
fun isFunc(v: Any?): Boolean = v is FuncVal

fun vecDim(v: Any): Int = when (v) {
    is Vec2 -> 2
    is Vec3 -> 3
    is Vec4 -> 4
    else -> throw ScriptException("not a vector")
}

fun vecComps(v: Any): List<Double> = when (v) {
    is Vec2 -> listOf(v.x, v.y)
    is Vec3 -> listOf(v.x, v.y, v.z)
    is Vec4 -> listOf(v.x, v.y, v.z, v.w)
    else -> throw ScriptException("not a vector")
}

fun mkVec(dim: Int, comps: List<Double>): Any = when (dim) {
    2 -> Vec2(comps[0], comps[1])
    3 -> Vec3(comps[0], comps[1], comps[2])
    4 -> Vec4(comps[0], comps[1], comps[2], comps[3])
    else -> throw ScriptException("invalid vector dimension $dim")
}

fun typeName(v: Any?): String = when {
    v == null -> "null"
    isNum(v) -> "num"
    isBool(v) -> "bool"
    isString(v) -> "string"
    isArray(v) -> "array"
    v is Vec2 -> "vec2"
    v is Vec3 -> "vec3"
    v is Vec4 -> "vec4"
    v is Mat3 -> "mat3"
    v is Mat4 -> "mat4"
    v is FuncVal -> "func"
    else -> "unknown"
}

// ---- JS 数值语义辅助 ----

/** JS Math.trunc：向零取整。 */
fun jsTrunc(x: Double): Double = if (x < 0.0) ceil(x) else floor(x)

/** JS Math.round：半值向 +∞ 取整。 */
fun jsRound(x: Double): Double {
    if (x.isNaN()) return x
    if (x == 0.0) return x
    if (x > 0.0 && x < 0.5) return 0.0
    if (x < 0.0 && x >= -0.5) return -0.0
    return floor(x + 0.5)
}

/** JS ToInt32（`x | 0`）语义：有限数截断后按 2^32 取模映射到有符号 Int。 */
fun toInt32(x: Double): Int {
    if (!x.isFinite()) return 0
    val t = jsTrunc(x)
    val mod = ((t % 4294967296.0) + 4294967296.0) % 4294967296.0
    val u = mod.toLong()
    return (if (u >= 2147483648L) u - 4294967296L else u).toInt()
}

fun clamp01(x: Double): Double = when {
    x.isNaN() -> 0.0
    x < 0.0 -> 0.0
    x > 1.0 -> 1.0
    else -> x
}

fun clampNum(x: Double, lo: Double, hi: Double): Double = when {
    x < lo -> lo
    x > hi -> hi
    else -> x
}
