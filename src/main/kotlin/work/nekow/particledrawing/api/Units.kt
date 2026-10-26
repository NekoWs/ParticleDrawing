package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import java.math.BigDecimal

// 显式单位值：时间、长度、角度、角速度、速度与加速度。
// Kotlin 用扩展属性（6.ticks、1.2.blocks），Java 用同名静态工厂；两者落到同一个值。
// 表达式里的单位保留单位类型，运算时只在边界换算成数值，不同量纲不能相加。

private fun finite(value: Double, what: String): Double {
    require(value.isFinite()) { "$what 必须是有限值" }
    return value
}

/**
 * 生成表达式可解析的十进制字面量。
 *
 * `Double.toString` 在极小/极大值上会给出 `1.5E-4` 这类科学计数法，而 PD 的一元公式词法
 * 不接受指数，`E` 还会被当成 Euler 常量。这里统一展开成普通十进制；[BigDecimal.valueOf]
 * 保留与 Double 往返一致的十进制值，只去掉无意义的尾零。
 */
internal fun numberLiteral(value: Double): String {
    require(value.isFinite()) { "数值字面量必须是有限值" }
    if (value == 0.0) return "0"
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

/** Float 走最短十进制表示，避免 0.1f.toDouble() 的二进制尾数写进表达式。 */
internal fun numberLiteral(value: Float): String {
    require(value.isFinite()) { "数值字面量必须是有限值" }
    if (value == 0.0f) return "0"
    return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}

/**
 * 时间。内部按毫秒保存，1 tick = 50ms。
 *
 * 既有 API 仍收整数 tick；需要取整时显式调用 [requireWholeTicks] / [roundedTicks] /
 * [floorTicks] / [ceilTicks]，不接受悄悄截断。
 */
class Time internal constructor(
    val millis: Double,
    internal val expr: String,
    private val constant: Boolean,
) {

    val ticks: Double get() = millis / 50.0
    val seconds: Double get() = millis / 1000.0

    /** 必须是整数 tick；小数刻调用方要显式选一种取整方式。 */
    fun requireWholeTicks(field: String = "时间"): Int {
        require(constant) { "$field 是表达式值，不能在这里做常量校验" }
        val ticks = ticks
        require(ticks.isFinite()) { "$field 不是有限值" }
        require(ticks >= 0.0) { "$field 不能为负" }
        require(ticks <= Int.MAX_VALUE.toDouble()) { "$field 超出 Int tick 范围" }
        require(kotlin.math.abs(ticks - kotlin.math.round(ticks)) <= 1e-6) {
            "$field 需要整数 tick，当前是 ${ticks}t；请显式 roundedTicks/floorTicks/ceilTicks"
        }
        return kotlin.math.round(ticks).toInt()
    }

    fun roundedTicks(): Int = kotlin.math.round(checkedTicks("时间")).toInt()
    fun floorTicks(): Int = kotlin.math.floor(checkedTicks("时间")).toInt()
    fun ceilTicks(): Int = kotlin.math.ceil(checkedTicks("时间")).toInt()

    internal fun isConstant(): Boolean = constant

    private fun checkedTicks(field: String): Double {
        require(constant) { "$field 是表达式值，不能在这里做常量校验" }
        val value = ticks
        require(value.isFinite()) { "$field 不是有限值" }
        require(value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()) {
            "$field 超出 Int tick 范围"
        }
        return value
    }

    /** 非负校验，返回自身便于链式。 */
    fun requireNonNegative(field: String = "时间"): Time {
        require(constant) { "$field 是表达式值，不能在这里做常量校验" }
        require(millis >= 0.0) { "$field 不能为负" }
        return this
    }

    /** 正数校验。 */
    fun requirePositive(field: String = "时间"): Time {
        require(constant) { "$field 是表达式值，不能在这里做常量校验" }
        require(millis > 0.0) { "$field 必须为正" }
        return this
    }

    internal fun render(): String = expr

    operator fun plus(other: Time): Time = binary(this, other, "+")
    operator fun minus(other: Time): Time = binary(this, other, "-")

    operator fun times(value: Scalar): Time {
        val const = constant && value.isConstant
        val result = millis * value.constantOrNaN()
        return Time(if (const) finite(result, "时间乘法") else Double.NaN, "($expr*${value.expr})", const)
    }

    operator fun div(value: Scalar): Time {
        val const = constant && value.isConstant
        val result = millis / value.constantOrNaN()
        return Time(if (const) finite(result, "时间除法") else Double.NaN, "($expr/${value.expr})", const)
    }

    operator fun div(other: Time): Scalar = Scalar(
        "($expr/${other.expr})",
        if (constant && other.constant) millis / other.millis else null,
    )

    private fun binary(a: Time, b: Time, op: String): Time {
        val const = a.constant && b.constant
        val result = if (op == "-") a.millis - b.millis else a.millis + b.millis
        return Time(if (const) finite(result, "时间运算") else Double.NaN, "(${a.expr}$op${b.expr})", const)
    }

    override fun equals(other: Any?): Boolean = other is Time && constant && other.constant && millis == other.millis
    override fun hashCode(): Int = millis.hashCode()
    override fun toString(): String = if (constant) "${ticks}t" else "Time($expr)"

    companion object {
        @JvmStatic
        fun ticks(ticks: Double): Time {
            finite(ticks, "时间")
            val millis = finite(ticks * 50.0, "时间")
            return Time(millis, numberLiteral(millis), true)
        }

        @JvmStatic
        fun ticks(ticks: Int): Time = ticks(ticks.toDouble())

        @JvmStatic
        fun milliseconds(millis: Double): Time {
            finite(millis, "时间")
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
class Length internal constructor(
    val blocks: Double,
    internal val expr: String,
    private val constant: Boolean,
) {

    val mcPixels: Double get() = finite(blocks * 16.0, "长度换算")

    fun requireNonNegative(field: String = "长度"): Length {
        require(constant) { "$field 是表达式值，不能在这里做常量校验" }
        require(blocks >= 0.0) { "$field 不能为负" }
        return this
    }

    fun requirePositive(field: String = "长度"): Length {
        require(constant) { "$field 是表达式值，不能在这里做常量校验" }
        require(blocks > 0.0) { "$field 必须为正" }
        return this
    }

    internal fun render(): String = expr

    operator fun plus(other: Length): Length = binary(this, other, "+")
    operator fun minus(other: Length): Length = binary(this, other, "-")
    operator fun unaryMinus(): Length = Length(-blocks, "(-$expr)", constant)

    operator fun times(value: Scalar): Length {
        val const = constant && value.isConstant
        val result = blocks * value.constantOrNaN()
        return Length(if (const) finite(result, "长度乘法") else Double.NaN, "($expr*${value.expr})", const)
    }

    operator fun div(value: Scalar): Length {
        val const = constant && value.isConstant
        val result = blocks / value.constantOrNaN()
        return Length(if (const) finite(result, "长度除法") else Double.NaN, "($expr/${value.expr})", const)
    }

    operator fun div(other: Length): Scalar = Scalar(
        "($expr/${other.expr})",
        if (constant && other.constant) blocks / other.blocks else null,
    )

    internal fun isConstant(): Boolean = constant

    private fun binary(a: Length, b: Length, op: String): Length {
        val const = a.constant && b.constant
        val result = if (op == "-") a.blocks - b.blocks else a.blocks + b.blocks
        return Length(if (const) finite(result, "长度运算") else Double.NaN, "(${a.expr}$op${b.expr})", const)
    }

    override fun equals(other: Any?): Boolean = other is Length && constant && other.constant && blocks == other.blocks
    override fun hashCode(): Int = blocks.hashCode()
    override fun toString(): String = if (constant) "${blocks}格" else "Length($expr)"

    companion object {
        @JvmStatic
        fun blocks(blocks: Double): Length {
            finite(blocks, "长度")
            return Length(blocks, numberLiteral(blocks), true)
        }

        @JvmStatic
        fun blocks(blocks: Int): Length = blocks(blocks.toDouble())

        @JvmStatic
        fun mcPixels(pixels: Double): Length {
            finite(pixels, "长度")
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
class Angle internal constructor(
    val radians: Double,
    internal val expr: String,
    private val constant: Boolean,
) {

    val degrees: Double get() = finite(Math.toDegrees(radians), "角度换算")

    internal fun render(): String = expr

    operator fun plus(other: Angle): Angle = binary(this, other, "+")
    operator fun minus(other: Angle): Angle = binary(this, other, "-")
    operator fun unaryMinus(): Angle = Angle(-radians, "(-$expr)", constant)

    operator fun times(value: Scalar): Angle {
        val const = constant && value.isConstant
        val result = radians * value.constantOrNaN()
        return Angle(if (const) finite(result, "角度乘法") else Double.NaN, "($expr*${value.expr})", const)
    }

    operator fun div(value: Scalar): Angle {
        val const = constant && value.isConstant
        val result = radians / value.constantOrNaN()
        return Angle(if (const) finite(result, "角度除法") else Double.NaN, "($expr/${value.expr})", const)
    }

    operator fun div(other: Angle): Scalar = Scalar(
        "($expr/${other.expr})",
        if (constant && other.constant) radians / other.radians else null,
    )

    operator fun div(other: Time): AngularSpeed {
        val const = constant && other.isConstant()
        val result = if (const) finite(radians / other.millis * 1000.0, "角度/时间") else Double.NaN
        return AngularSpeed(result, "($expr/${other.expr})", const)
    }

    internal fun isConstant(): Boolean = constant

    private fun binary(a: Angle, b: Angle, op: String): Angle {
        val const = a.constant && b.constant
        val result = if (op == "-") a.radians - b.radians else a.radians + b.radians
        return Angle(if (const) finite(result, "角度运算") else Double.NaN, "(${a.expr}$op${b.expr})", const)
    }

    override fun equals(other: Any?): Boolean = other is Angle && constant && other.constant && radians == other.radians
    override fun hashCode(): Int = radians.hashCode()
    override fun toString(): String = if (constant) "${degrees}°" else "Angle($expr)"

    companion object {
        @JvmStatic
        fun radians(radians: Double): Angle {
            finite(radians, "角度")
            return Angle(radians, numberLiteral(radians), true)
        }

        @JvmStatic
        fun degrees(degrees: Double): Angle {
            finite(degrees, "角度")
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
class AngularSpeed internal constructor(
    val radiansPerSecond: Double,
    internal val expr: String,
    private val constant: Boolean,
) {

    val radiansPerTick: Double get() = radiansPerSecond / 20.0

    internal fun render(): String = expr

    /** 内部 expr 统一为弧度/毫秒；乘以毫秒时间只换算一次。 */
    operator fun times(time: Time): Angle {
        val const = constant && time.isConstant()
        val result = radiansPerSecond * (time.millis / 1000.0)
        return Angle(if (const) finite(result, "角速度×时间") else Double.NaN, "($expr*${time.expr})", const)
    }

    internal fun isConstant(): Boolean = constant

    override fun toString(): String =
        if (constant) "${radiansPerSecond}rad/s" else "AngularSpeed($expr)"

    companion object {
        @JvmStatic
        fun radiansPerSecond(value: Double): AngularSpeed {
            finite(value, "角速度")
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
            Velocity(Vec3(finite(x, "速度"), finite(y, "速度"), finite(z, "速度")))

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
            Acceleration(Vec3(finite(x, "加速度"), finite(y, "加速度"), finite(z, "加速度")))

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

    fun requireFinite(field: String = "寿命"): Int {
        require(!infinite) { "$field 需要有限值，当前是无限" }
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
            require(ticks >= 0) { "有限寿命不能为负" }
            return Lifetime(ticks, false)
        }

        @JvmStatic
        fun of(time: Time): Lifetime = ticks(time.requireWholeTicks("寿命"))
    }
}
