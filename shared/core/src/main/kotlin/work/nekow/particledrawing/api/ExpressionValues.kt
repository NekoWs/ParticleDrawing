package work.nekow.particledrawing.api

import work.nekow.particledrawing.math.Vec3
import kotlin.math.PI

// 可读表达式门面：构建一次标量表达式，最终仍交给 ParticleGroup.expression 的现有后端。
// 单位类型只决定生成文本和编译期能否混用；运行期仍然是既有的逐帧标量求值，不新增播放器。

/** 无量纲标量表达式。 */
class Scalar(
    val expr: String,
    private val constantValue: Double? = null,
) {

    init {
        require(constantValue == null || constantValue.isFinite()) { "标量常量必须是有限值" }
    }

    val isConstant: Boolean get() = constantValue != null

    fun constantOrNaN(): Double = constantValue ?: Double.NaN

    /** 常量值；表达式值返回 null。 */
    fun constant(): Double? = constantValue

    operator fun plus(other: Scalar): Scalar = binary(other, "+")
    operator fun minus(other: Scalar): Scalar = binary(other, "-")
    operator fun times(other: Scalar): Scalar = binary(other, "*")
    operator fun div(other: Scalar): Scalar = binary(other, "/")
    operator fun rem(other: Scalar): Scalar = binary(other, "%")
    operator fun unaryMinus(): Scalar = Scalar("(-$expr)", constantValue?.let { -it })

    operator fun plus(value: Double): Scalar = this + of(value)
    operator fun minus(value: Double): Scalar = this - of(value)
    operator fun times(value: Double): Scalar = this * of(value)
    operator fun div(value: Double): Scalar = this / of(value)

    private fun binary(other: Scalar, op: String): Scalar {
        val a = constantValue
        val b = other.constantValue
        val value = if (a != null && b != null) when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> a / b
            "%" -> a % b
            else -> null
        } else null
        return Scalar("($expr$op${other.expr})", value)
    }

    override fun toString(): String = expr

    companion object {
        @JvmStatic
        fun of(value: Double): Scalar {
            require(value.isFinite()) { "标量必须是有限值" }
            return Scalar(numberLiteral(value), value)
        }
    }
}

fun Double.toScalar(): Scalar = Scalar.of(this)
fun Int.toScalar(): Scalar = Scalar.of(toDouble())

operator fun Double.plus(value: Scalar): Scalar = Scalar.of(this) + value
operator fun Double.minus(value: Scalar): Scalar = Scalar.of(this) - value
operator fun Double.times(value: Scalar): Scalar = Scalar.of(this) * value
operator fun Double.div(value: Scalar): Scalar = Scalar.of(this) / value

operator fun Scalar.times(length: Length): Length {
    val const = isConstant && length.isConstant()
    val result = constantOrNaN() * length.blocks
    require(!const || result.isFinite()) { "长度乘法溢出或非有限" }
    return Length(if (const) result else Double.NaN, "($expr*${length.expr})", const)
}

operator fun Scalar.times(time: Time): Time {
    val const = isConstant && time.isConstant()
    val result = constantOrNaN() * time.millis
    require(!const || result.isFinite()) { "时间乘法溢出或非有限" }
    return Time(if (const) result else Double.NaN, "($expr*${time.expr})", const)
}

operator fun Scalar.times(angle: Angle): Angle {
    val const = isConstant && angle.isConstant()
    val result = constantOrNaN() * angle.radians
    require(!const || result.isFinite()) { "角度乘法溢出或非有限" }
    return Angle(if (const) result else Double.NaN, "($expr*${angle.expr})", const)
}

fun sin(value: Scalar): Scalar = Scalar("sin(${value.expr})")
fun cos(value: Scalar): Scalar = Scalar("cos(${value.expr})")
fun tan(value: Scalar): Scalar = Scalar("tan(${value.expr})")
fun asin(value: Scalar): Scalar = Scalar("asin(${value.expr})")
fun acos(value: Scalar): Scalar = Scalar("acos(${value.expr})")
fun atan(value: Scalar): Scalar = Scalar("atan(${value.expr})")
fun atan2(y: Scalar, x: Scalar): Scalar = Scalar("atan2(${y.expr},${x.expr})")
fun sqrt(value: Scalar): Scalar = Scalar("sqrt(${value.expr})")
fun abs(value: Scalar): Scalar = Scalar("abs(${value.expr})")
fun floor(value: Scalar): Scalar = Scalar("floor(${value.expr})")
fun ceil(value: Scalar): Scalar = Scalar("ceil(${value.expr})")
fun round(value: Scalar): Scalar = Scalar("round(${value.expr})")
fun exp(value: Scalar): Scalar = Scalar("exp(${value.expr})")
fun log(value: Scalar): Scalar = Scalar("log(${value.expr})")
fun sign(value: Scalar): Scalar = Scalar("sign(${value.expr})")
fun fract(value: Scalar): Scalar = Scalar("fract(${value.expr})")
fun pow(base: Scalar, exponent: Scalar): Scalar {
    val value = if (base.isConstant && exponent.isConstant) {
        Math.pow(base.constant()!!, exponent.constant()!!)
    } else null
    return Scalar("pow(${base.expr},${exponent.expr})", value)
}

fun pow(base: Scalar, exponent: Number): Scalar = pow(base, Scalar.of(exponent.toDouble()))

fun min(a: Scalar, b: Scalar): Scalar = Scalar("min(${a.expr},${b.expr})")
fun max(a: Scalar, b: Scalar): Scalar = Scalar("max(${a.expr},${b.expr})")
fun clamp(value: Scalar, minValue: Scalar, maxValue: Scalar): Scalar =
    Scalar("clamp(${value.expr},${minValue.expr},${maxValue.expr})")
fun step(edge: Scalar, value: Scalar): Scalar = Scalar("step(${edge.expr},${value.expr})")
fun smoothstep(edge0: Scalar, edge1: Scalar, value: Scalar): Scalar =
    Scalar("smoothstep(${edge0.expr},${edge1.expr},${value.expr})")
fun lerp(from: Scalar, to: Scalar, t: Scalar): Scalar = Scalar(lerpExpr(from.expr, to.expr, t.expr))
fun square(value: Scalar): Scalar = Scalar("(${value.expr}*${value.expr})")
fun clamp01(value: Scalar): Scalar = clamp(value, Scalar.of(0.0), Scalar.of(1.0))

/** 高级表达式引擎没有全局 lerp，这里直接展开成算术。 */
private fun lerpExpr(from: String, to: String, t: String): String = "(($from)+(($to)-($from))*($t))"

fun sin(angle: Angle): Scalar = sin(Scalar(angle.expr))
fun cos(angle: Angle): Scalar = cos(Scalar(angle.expr))
fun tan(angle: Angle): Scalar = tan(Scalar(angle.expr))

fun lerp(from: Length, to: Length, t: Scalar): Length =
    Length(Double.NaN, lerpExpr(from.expr, to.expr, t.expr), false)

fun lerp(from: Angle, to: Angle, t: Scalar): Angle =
    Angle(Double.NaN, lerpExpr(from.expr, to.expr, t.expr), false)

/** 单位值转成表达式标量；长度进入世界格数值，角度进入弧度，时间进入毫秒。 */
fun Length.toScalar(): Scalar = Scalar(expr)
fun Angle.toScalar(): Scalar = Scalar(expr)
fun Time.toScalar(): Scalar = Scalar(expr)

/** Java 静态标量数学入口。 */
object Scalars {
    @JvmStatic fun of(value: Double): Scalar = Scalar.of(value)
    @JvmStatic fun sin(value: Scalar): Scalar = work.nekow.particledrawing.api.sin(value)
    @JvmStatic fun sin(value: Angle): Scalar = work.nekow.particledrawing.api.sin(value)
    @JvmStatic fun cos(value: Scalar): Scalar = work.nekow.particledrawing.api.cos(value)
    @JvmStatic fun cos(value: Angle): Scalar = work.nekow.particledrawing.api.cos(value)
    @JvmStatic fun square(value: Scalar): Scalar = work.nekow.particledrawing.api.square(value)
    @JvmStatic fun pow(base: Scalar, exponent: Scalar): Scalar =
        work.nekow.particledrawing.api.pow(base, exponent)
    @JvmStatic fun pow(base: Scalar, exponent: Number): Scalar =
        work.nekow.particledrawing.api.pow(base, exponent)
    @JvmStatic fun clamp01(value: Scalar): Scalar = work.nekow.particledrawing.api.clamp01(value)
    @JvmStatic fun smoothstep(edge0: Scalar, edge1: Scalar, value: Scalar): Scalar =
        work.nekow.particledrawing.api.smoothstep(edge0, edge1, value)
    @JvmStatic fun lerp(from: Scalar, to: Scalar, t: Scalar): Scalar =
        work.nekow.particledrawing.api.lerp(from, to, t)
    @JvmStatic fun lerp(from: Length, to: Length, t: Scalar): Length =
        work.nekow.particledrawing.api.lerp(from, to, t)
    @JvmField val goldenAngle: Scalar = Angles.goldenAngle
}

/** 位置用向量；各分量是世界格长度。 */
class Vector(
    val x: Length,
    val y: Length,
    val z: Length,
) {

    operator fun plus(other: Vector): Vector =
        Vector(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vector): Vector =
        Vector(x - other.x, y - other.y, z - other.z)

    operator fun times(value: Scalar): Vector =
        Vector(x * value, y * value, z * value)

    operator fun div(value: Scalar): Vector =
        Vector(x / value, y / value, z / value)

    override fun toString(): String = "(${x.render()},${y.render()},${z.render()})"
}

fun vec3(x: Length, y: Length, z: Length): Vector = Vector(x, y, z)

/** 单位方向；乘以长度得到世界向量。 */
class Direction(val vector: Vector) {
    operator fun times(length: Length): Vector = Vector(
        Length(Double.NaN, "(${vector.x.expr}*${length.expr})", false),
        Length(Double.NaN, "(${vector.y.expr}*${length.expr})", false),
        Length(Double.NaN, "(${vector.z.expr}*${length.expr})", false),
    )
}

/** 参数类型，供调试导出。 */
data class ParameterInfo(val name: String, val kind: String, val unit: String)

val PARAM_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
val RESERVED_PARAM_NAMES = setOf(
    "i", "n", "t", "PI", "E",
    "x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light",
)

/** 固定/常量表。 */
object Angles {
    /** 黄金角（弧度），用于稳定的成员分布。 */
    @JvmField val goldenAngle: Scalar = Scalar.of(2.399963229728653)

    @JvmField val PI_SCALAR: Scalar = Scalar.of(PI)
}
