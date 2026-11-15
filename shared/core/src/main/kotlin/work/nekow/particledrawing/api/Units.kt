package work.nekow.particledrawing.api

import work.nekow.particledrawing.math.Vec3
import java.math.BigDecimal
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

// 显式单位值：时间、长度、角度、角速度、速度与加速度。
// Kotlin 用扩展属性（6.ticks、1.2.blocks），Java 用同名静态工厂；两者落到同一个值。
// 表达式里的单位保留单位类型，运算时只在边界换算成数值，不同量纲不能相加。

private fun finite(value: Double, fieldKey: String): Double {
    requireLocalized(value.isFinite()) { translation("error.parameter.finite", translation(fieldKey)) }
    return value
}

/**
 * 生成表达式可解析的十进制字面量。
 *
 * `Double.toString` 在极小/极大值上会给出 `1.5E-4` 这类科学计数法，而 PD 的一元公式词法
 * 不接受指数，`E` 还会被当成 Euler 常量。这里统一展开成普通十进制；[BigDecimal.valueOf]
 * 保留与 Double 往返一致的十进制值，只去掉无意义的尾零。
 */
fun numberLiteral(value: Double): String {
    requireLocalized(value.isFinite()) { translation("error.number.finite") }
    if (value == 0.0) return "0"
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

/** Float 走最短十进制表示，避免 0.1f.toDouble() 的二进制尾数写进表达式。 */
fun numberLiteral(value: Float): String {
    requireLocalized(value.isFinite()) { translation("error.number.finite") }
    if (value == 0.0f) return "0"
    return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}

/**
 * 时间。内部按毫秒保存，1 tick = 50ms。
 *
 * 既有 API 仍收整数 tick；需要取整时显式调用 [requireWholeTicks] / [roundedTicks] /
 * [floorTicks] / [ceilTicks]，不接受悄悄截断。
 */
class Time(
    val millis: Double,
    val expr: String,
    private val constant: Boolean,
) {

    val ticks: Double get() = millis / 50.0
    val seconds: Double get() = millis / 1000.0

    /** 必须是整数 tick；小数刻调用方要显式选一种取整方式。 */
    fun requireWholeTicks(field: String = translation("field.time").resolve()): Int {
        requireLocalized(constant) { translation("error.parameter.constant", field) }
        val ticks = ticks
        requireLocalized(ticks.isFinite()) { translation("error.parameter.finite", field) }
        requireLocalized(ticks >= 0.0) { translation("error.parameter.nonnegative", field) }
        requireLocalized(ticks <= Int.MAX_VALUE.toDouble()) { translation("error.parameter.tick_range", field) }
        requireLocalized(kotlin.math.abs(ticks - kotlin.math.round(ticks)) <= 1e-6) {
            translation("error.parameter.whole_ticks", field, ticks)
        }
        return kotlin.math.round(ticks).toInt()
    }

    fun roundedTicks(): Int = kotlin.math.round(checkedTicks()).toInt()
    fun floorTicks(): Int = kotlin.math.floor(checkedTicks()).toInt()
    fun ceilTicks(): Int = kotlin.math.ceil(checkedTicks()).toInt()

    fun isConstant(): Boolean = constant

    private fun checkedTicks(): Double {
        requireLocalized(constant) { translation("error.parameter.constant", translation("field.time")) }
        val value = ticks
        requireLocalized(value.isFinite()) { translation("error.parameter.finite", translation("field.time")) }
        requireLocalized(value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()) {
            translation("error.parameter.tick_range", translation("field.time"))
        }
        return value
    }

    /** 非负校验，返回自身便于链式。 */
    fun requireNonNegative(field: String = translation("field.time").resolve()): Time {
        requireLocalized(constant) { translation("error.parameter.constant", field) }
        requireLocalized(millis >= 0.0) { translation("error.parameter.nonnegative", field) }
        return this
    }

    /** 正数校验。 */
    fun requirePositive(field: String = translation("field.time").resolve()): Time {
        requireLocalized(constant) { translation("error.parameter.constant", field) }
        requireLocalized(millis > 0.0) { translation("error.parameter.positive", field) }
        return this
    }

    fun render(): String = expr

    operator fun plus(other: Time): Time = binary(this, other, "+")
    operator fun minus(other: Time): Time = binary(this, other, "-")

    operator fun times(value: Scalar): Time {
        val const = constant && value.isConstant
        val result = millis * value.constantOrNaN()
        return Time(if (const) finite(result, "field.time_product") else Double.NaN, "($expr*${value.expr})", const)
    }

    operator fun div(value: Scalar): Time {
        val const = constant && value.isConstant
        val result = millis / value.constantOrNaN()
        return Time(if (const) finite(result, "field.time_quotient") else Double.NaN, "($expr/${value.expr})", const)
    }

    operator fun div(other: Time): Scalar = Scalar(
        "($expr/${other.expr})",
        if (constant && other.constant) millis / other.millis else null,
    )

    private fun binary(a: Time, b: Time, op: String): Time {
        val const = a.constant && b.constant
        val result = if (op == "-") a.millis - b.millis else a.millis + b.millis
        return Time(if (const) finite(result, "field.time_calculation") else Double.NaN, "(${a.expr}$op${b.expr})", const)
    }

    override fun equals(other: Any?): Boolean = other is Time && constant && other.constant && millis == other.millis
    override fun hashCode(): Int = millis.hashCode()
    override fun toString(): String = if (constant) "${ticks}t" else "Time($expr)"

    companion object {
        @JvmStatic
        fun ticks(ticks: Double): Time {
            finite(ticks, "field.time")
            val millis = finite(ticks * 50.0, "field.time")
            return Time(millis, numberLiteral(millis), true)
        }

        @JvmStatic
        fun ticks(ticks: Int): Time = ticks(ticks.toDouble())

        @JvmStatic
        fun milliseconds(millis: Double): Time {
            finite(millis, "field.time")
            return Time(millis, numberLiteral(millis), true)
        }

        @JvmStatic
        fun milliseconds(millis: Int): Time = milliseconds(millis.toDouble())

        @JvmStatic
        fun seconds(seconds: Double): Time = milliseconds(seconds * 1000.0)
    }
}

val Int.ticks: Time get() = Time.ticks(this)
val Double.ticks: Time get() = Time.ticks(this)
val Int.milliseconds: Time get() = Time.milliseconds(this)
val Double.milliseconds: Time get() = Time.milliseconds(this)
val Int.seconds: Time get() = Time.seconds(toDouble())
val Double.seconds: Time get() = Time.seconds(this)

/**
 * 世界长度。内部以 Minecraft 格保存；[Length.mcPixels] 是 1/16 格，不是贴图像素或编辑器 scale。
 */
class Length(
    val blocks: Double,
    val expr: String,
    private val constant: Boolean,
) {

    val mcPixels: Double get() = finite(blocks * 16.0, "field.length_conversion")

    fun requireNonNegative(field: String = translation("field.length").resolve()): Length {
        requireLocalized(constant) { translation("error.parameter.constant", field) }
        requireLocalized(blocks >= 0.0) { translation("error.parameter.nonnegative", field) }
        return this
    }

    fun requirePositive(field: String = translation("field.length").resolve()): Length {
        requireLocalized(constant) { translation("error.parameter.constant", field) }
        requireLocalized(blocks > 0.0) { translation("error.parameter.positive", field) }
        return this
    }

    fun render(): String = expr

    operator fun plus(other: Length): Length = binary(this, other, "+")
    operator fun minus(other: Length): Length = binary(this, other, "-")
    operator fun unaryMinus(): Length = Length(-blocks, "(-$expr)", constant)

    operator fun times(value: Scalar): Length {
        val const = constant && value.isConstant
        val result = blocks * value.constantOrNaN()
        return Length(if (const) finite(result, "field.length_product") else Double.NaN, "($expr*${value.expr})", const)
    }

    operator fun div(value: Scalar): Length {
        val const = constant && value.isConstant
        val result = blocks / value.constantOrNaN()
        return Length(if (const) finite(result, "field.length_quotient") else Double.NaN, "($expr/${value.expr})", const)
    }

    operator fun div(other: Length): Scalar = Scalar(
        "($expr/${other.expr})",
        if (constant && other.constant) blocks / other.blocks else null,
    )

    fun isConstant(): Boolean = constant

    private fun binary(a: Length, b: Length, op: String): Length {
        val const = a.constant && b.constant
        val result = if (op == "-") a.blocks - b.blocks else a.blocks + b.blocks
        return Length(if (const) finite(result, "field.length_calculation") else Double.NaN, "(${a.expr}$op${b.expr})", const)
    }

    override fun equals(other: Any?): Boolean = other is Length && constant && other.constant && blocks == other.blocks
    override fun hashCode(): Int = blocks.hashCode()
    override fun toString(): String = if (constant) translation("unit.blocks", blocks).resolve() else "Length($expr)"

    companion object {
        @JvmStatic
        fun blocks(blocks: Double): Length {
            finite(blocks, "field.length")
            return Length(blocks, numberLiteral(blocks), true)
        }

        @JvmStatic
        fun blocks(blocks: Int): Length = blocks(blocks.toDouble())

        @JvmStatic
        fun mcPixels(pixels: Double): Length {
            finite(pixels, "field.length")
            val blocks = pixels / 16.0
            return Length(blocks, numberLiteral(blocks), true)
        }

        @JvmStatic
        fun mcPixels(pixels: Int): Length = mcPixels(pixels.toDouble())
    }
}

val Int.blocks: Length get() = Length.blocks(this)
val Double.blocks: Length get() = Length.blocks(this)
val Int.mcPixels: Length get() = Length.mcPixels(this)
val Double.mcPixels: Length get() = Length.mcPixels(this)

/** 角度。内部以弧度保存；[Angle.degrees] / [Angle.radians] 是同一值的两种入口。 */
class Angle(
    val radians: Double,
    val expr: String,
    private val constant: Boolean,
) {

    val degrees: Double get() = finite(Math.toDegrees(radians), "field.angle_conversion")

    fun render(): String = expr

    operator fun plus(other: Angle): Angle = binary(this, other, "+")
    operator fun minus(other: Angle): Angle = binary(this, other, "-")
    operator fun unaryMinus(): Angle = Angle(-radians, "(-$expr)", constant)

    operator fun times(value: Scalar): Angle {
        val const = constant && value.isConstant
        val result = radians * value.constantOrNaN()
        return Angle(if (const) finite(result, "field.angle_product") else Double.NaN, "($expr*${value.expr})", const)
    }

    operator fun div(value: Scalar): Angle {
        val const = constant && value.isConstant
        val result = radians / value.constantOrNaN()
        return Angle(if (const) finite(result, "field.angle_quotient") else Double.NaN, "($expr/${value.expr})", const)
    }

    operator fun div(other: Angle): Scalar = Scalar(
        "($expr/${other.expr})",
        if (constant && other.constant) radians / other.radians else null,
    )

    operator fun div(other: Time): AngularSpeed {
        val const = constant && other.isConstant()
        val result = if (const) finite(radians / other.millis * 1000.0, "field.angle_rate") else Double.NaN
        return AngularSpeed(result, "($expr/${other.expr})", const)
    }

    fun isConstant(): Boolean = constant

    private fun binary(a: Angle, b: Angle, op: String): Angle {
        val const = a.constant && b.constant
        val result = if (op == "-") a.radians - b.radians else a.radians + b.radians
        return Angle(if (const) finite(result, "field.angle_calculation") else Double.NaN, "(${a.expr}$op${b.expr})", const)
    }

    override fun equals(other: Any?): Boolean = other is Angle && constant && other.constant && radians == other.radians
    override fun hashCode(): Int = radians.hashCode()
    override fun toString(): String = if (constant) "${degrees}°" else "Angle($expr)"

    companion object {
        @JvmStatic
        fun radians(radians: Double): Angle {
            finite(radians, "field.angle")
            return Angle(radians, numberLiteral(radians), true)
        }

        @JvmStatic
        fun degrees(degrees: Double): Angle {
            finite(degrees, "field.angle")
            val radians = Math.toRadians(degrees)
            return Angle(radians, numberLiteral(radians), true)
        }

        @JvmStatic
        fun degrees(degrees: Int): Angle = degrees(degrees.toDouble())
    }
}

val Int.degrees: Angle get() = Angle.degrees(this)
val Double.degrees: Angle get() = Angle.degrees(this)
val Int.radians: Angle get() = Angle.radians(toDouble())
val Double.radians: Angle get() = Angle.radians(this)

/** 角速度。常量入口按弧度/秒；表达式内部按弧度/毫秒计算，乘以 [Time] 得到 [Angle]。 */
class AngularSpeed(
    val radiansPerSecond: Double,
    val expr: String,
    private val constant: Boolean,
) {

    val radiansPerTick: Double get() = radiansPerSecond / 20.0

    fun render(): String = expr

    /** 内部 expr 统一为弧度/毫秒；乘以毫秒时间只换算一次。 */
    operator fun times(time: Time): Angle {
        val const = constant && time.isConstant()
        val result = radiansPerSecond * (time.millis / 1000.0)
        return Angle(if (const) finite(result, "field.angular_speed_product") else Double.NaN, "($expr*${time.expr})", const)
    }

    fun isConstant(): Boolean = constant

    override fun toString(): String =
        if (constant) "${radiansPerSecond}rad/s" else "AngularSpeed($expr)"

    companion object {
        @JvmStatic
        fun radiansPerSecond(value: Double): AngularSpeed {
            finite(value, "field.angular_speed")
            val perMillis = value / 1000.0
            return AngularSpeed(value, numberLiteral(perMillis), true)
        }

        @JvmStatic
        fun degreesPerSecond(value: Double): AngularSpeed =
            radiansPerSecond(Math.toRadians(value))
    }
}

val Int.radiansPerSecond: AngularSpeed get() = AngularSpeed.radiansPerSecond(toDouble())
val Double.radiansPerSecond: AngularSpeed get() = AngularSpeed.radiansPerSecond(this)
val Int.degreesPerSecond: AngularSpeed get() = AngularSpeed.degreesPerSecond(toDouble())
val Double.degreesPerSecond: AngularSpeed get() = AngularSpeed.degreesPerSecond(this)

/** 速度，单位 blocks/tick（与既有运动指令一致）。 */
class Velocity private constructor(val vector: Vec3) {

    val blocksPerTick: Vec3 get() = vector

    fun toVec3(): Vec3 = vector

    override fun toString(): String = "Velocity(${vector})"

    companion object {
        @JvmStatic
        fun blocksPerTick(x: Double, y: Double, z: Double): Velocity =
            Velocity(Vec3(finite(x, "field.velocity"), finite(y, "field.velocity"), finite(z, "field.velocity")))

        @JvmStatic
        fun blocksPerTick(vector: Vec3): Velocity =
            blocksPerTick(vector.x, vector.y, vector.z)

        @JvmStatic
        fun blocksPerSecond(x: Double, y: Double, z: Double): Velocity =
            blocksPerTick(x / 20.0, y / 20.0, z / 20.0)
    }
}

/** 加速度，单位 blocks/tick²。 */
class Acceleration private constructor(val vector: Vec3) {

    val blocksPerTickSquared: Vec3 get() = vector

    fun toVec3(): Vec3 = vector

    override fun toString(): String = "Acceleration(${vector})"

    companion object {
        @JvmStatic
        fun blocksPerTickSquared(x: Double, y: Double, z: Double): Acceleration =
            Acceleration(Vec3(finite(x, "field.acceleration"), finite(y, "field.acceleration"), finite(z, "field.acceleration")))

        @JvmStatic
        fun blocksPerTickSquared(vector: Vec3): Acceleration =
            blocksPerTickSquared(vector.x, vector.y, vector.z)

        @JvmStatic
        fun blocksPerSecondSquared(x: Double, y: Double, z: Double): Acceleration =
            blocksPerTickSquared(x / 400.0, y / 400.0, z / 400.0)
    }
}

/** 寿命：有限 tick 或无限。无限不再用 -1 字面量表达，内部仍映射到既有接口。 */
class Lifetime private constructor(
    val ticks: Int,
    val infinite: Boolean,
) {

    fun toTicksOrInfinite(): Int = if (infinite) -1 else ticks

    fun requireFinite(field: String = translation("field.lifetime").resolve()): Int {
        requireLocalized(!infinite) { translation("error.parameter.finite_lifetime", field) }
        return ticks
    }

    override fun equals(other: Any?): Boolean =
        other is Lifetime && infinite == other.infinite && ticks == other.ticks

    override fun hashCode(): Int = if (infinite) -1 else ticks
    override fun toString(): String = if (infinite) "Lifetime.infinite" else "${ticks}t"

    companion object {
        @JvmStatic
        fun infinite(): Lifetime = Lifetime(-1, true)

        @JvmStatic
        fun ticks(ticks: Int): Lifetime {
            requireLocalized(ticks >= 0) { translation("error.units.lifetime_negative") }
            return Lifetime(ticks, false)
        }

        @JvmStatic
        fun of(time: Time): Lifetime = ticks(time.requireWholeTicks(translation("field.lifetime").resolve()))
    }
}
