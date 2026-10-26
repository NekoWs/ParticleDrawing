package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import kotlin.math.PI

// 可读表达式门面：构建一次标量表达式，最终仍交给 ParticleGroup.expression 的现有后端。
// 单位类型只决定生成文本和编译期能否混用；运行期仍然是既有的逐帧标量求值，不新增播放器。

/** 无量纲标量表达式。 */
class Scalar internal constructor(
    val expr: String,
    private val constantValue: Double? = null,
) {

    val isConstant: Boolean get() = constantValue != null

    internal fun constantOrNaN(): Double = constantValue ?: Double.NaN

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
            return Scalar(value.toString(), value)
        }
    }
}

fun Double.toScalar(): Scalar = Scalar.of(this)
fun Int.toScalar(): Scalar = Scalar.of(toDouble())

operator fun Double.plus(value: Scalar): Scalar = Scalar.of(this) + value
operator fun Double.minus(value: Scalar): Scalar = Scalar.of(this) - value
operator fun Double.times(value: Scalar): Scalar = Scalar.of(this) * value
operator fun Double.div(value: Scalar): Scalar = Scalar.of(this) / value

operator fun Scalar.plus(length: Length): Length = length(length, "+")
operator fun Scalar.minus(length: Length): Length = length(length, "-")
operator fun Scalar.times(length: Length): Length = Length(
    (constantOrNaN() * length.blocks),
    "($expr*${length.expr})",
    false,
)
operator fun Scalar.times(time: Time): Time = Time(
    (constantOrNaN() * time.millis),
    "($expr*${time.expr})",
    false,
)
operator fun Scalar.times(angle: Angle): Angle = Angle(
    (constantOrNaN() * angle.radians),
    "($expr*${angle.expr})",
    false,
)

private fun Scalar.length(length: Length, op: String): Length =
    Length(Double.NaN, "($expr$op${length.expr})", false)

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
fun min(a: Scalar, b: Scalar): Scalar = Scalar("min(${a.expr},${b.expr})")
fun max(a: Scalar, b: Scalar): Scalar = Scalar("max(${a.expr},${b.expr})")
fun clamp(value: Scalar, minValue: Scalar, maxValue: Scalar): Scalar =
    Scalar("clamp(${value.expr},${minValue.expr},${maxValue.expr})")
fun step(edge: Scalar, value: Scalar): Scalar = Scalar("step(${edge.expr},${value.expr})")
fun smoothstep(edge0: Scalar, edge1: Scalar, value: Scalar): Scalar =
    Scalar("smoothstep(${edge0.expr},${edge1.expr},${value.expr})")
fun lerp(from: Scalar, to: Scalar, t: Scalar): Scalar = Scalar("lerp(${from.expr},${to.expr},${t.expr})")
fun square(value: Scalar): Scalar = Scalar("(${value.expr}*${value.expr})")
fun clamp01(value: Scalar): Scalar = clamp(value, Scalar.of(0.0), Scalar.of(1.0))

fun sin(angle: Angle): Scalar = sin(Scalar(angle.expr))
fun cos(angle: Angle): Scalar = cos(Scalar(angle.expr))
fun tan(angle: Angle): Scalar = tan(Scalar(angle.expr))

fun lerp(from: Length, to: Length, t: Scalar): Length =
    Length(Double.NaN, "lerp(${from.expr},${to.expr},${t.expr})", false)

fun lerp(from: Angle, to: Angle, t: Scalar): Angle =
    Angle(Double.NaN, "lerp(${from.expr},${to.expr},${t.expr})", false)

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
class Vector internal constructor(
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
class Direction internal constructor(val vector: Vector) {
    operator fun times(length: Length): Vector = Vector(
        Length(Double.NaN, "(${vector.x.expr}*${length.expr})", false),
        Length(Double.NaN, "(${vector.y.expr}*${length.expr})", false),
        Length(Double.NaN, "(${vector.z.expr}*${length.expr})", false),
    )
}

/** 参数类型，供调试导出。 */
data class ParameterInfo(val name: String, val kind: String, val unit: String)

private val PARAM_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
private val RESERVED_PARAM_NAMES = setOf(
    "i", "n", "t", "PI", "E",
    "x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light",
)

private fun ParticleGroup.requireParameterName(name: String) {
    require(PARAM_NAME.matches(name)) { "参数名 '$name' 不是合法标识符" }
    require(name !in RESERVED_PARAM_NAMES) { "参数名 '$name' 与内建名字冲突" }
    require(name !in readableParameterNames) { "参数名 '$name' 已经绑定过" }
}

private fun ParticleGroup.registerParameter(name: String, kind: String, unit: String, values: Vec3) {
    requireParameterName(name)
    for (suffix in listOf("_x", "_y", "_z")) {
        require("$name$suffix" !in readableParameterNames) {
            "参数名 '$name' 的分量 '$name$suffix' 已经绑定过"
        }
    }
    readableParameterNames.add(name)
    readableParameterNames.add("${name}_x")
    readableParameterNames.add("${name}_y")
    readableParameterNames.add("${name}_z")
    readableParameters.add(ParameterInfo(name, kind, unit))
    setVariable("${name}_x", values.x)
    setVariable("${name}_y", values.y)
    setVariable("${name}_z", values.z)
}

/** 可更新的向量参数句柄；表达式引用的是同名变量分量。 */
class VectorParameter internal constructor(
    private val group: ParticleGroup,
    val name: String,
) {

    val value: Vector
        get() = Vector(
            Length(Double.NaN, "${name}_x", false),
            Length(Double.NaN, "${name}_y", false),
            Length(Double.NaN, "${name}_z", false),
        )

    fun set(value: Vec3): VectorParameter {
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) { "向量参数必须是有限值" }
        group.setVariableLive("${name}_x", value.x.toString())
        group.setVariableLive("${name}_y", value.y.toString())
        group.setVariableLive("${name}_z", value.z.toString())
        return this
    }

    fun set(value: Vec3, over: Time, easing: Ease = Ease.LINEAR): VectorParameter {
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) { "向量参数必须是有限值" }
        over.requirePositive("参数过渡时长")
        val ticks = over.requireWholeTicks("参数过渡时长")
        group.setVariableInterpolated("${name}_x", value.x.toString(), ticks, easing.toEasingType())
        group.setVariableInterpolated("${name}_y", value.y.toString(), ticks, easing.toEasingType())
        group.setVariableInterpolated("${name}_z", value.z.toString(), ticks, easing.toEasingType())
        return this
    }
}

/** 方向参数句柄；set 时自动归一化。 */
class DirectionParameter internal constructor(
    private val group: ParticleGroup,
    val name: String,
) {

    val value: Direction
        get() = Direction(
            Vector(
                Length(Double.NaN, "${name}_x", false),
                Length(Double.NaN, "${name}_y", false),
                Length(Double.NaN, "${name}_z", false),
            )
        )

    fun set(value: Vec3): DirectionParameter {
        val unit = normalize(value)
        group.setVariableLive("${name}_x", unit.x.toString())
        group.setVariableLive("${name}_y", unit.y.toString())
        group.setVariableLive("${name}_z", unit.z.toString())
        return this
    }

    fun set(value: Vec3, over: Time, easing: Ease = Ease.LINEAR): DirectionParameter {
        val unit = normalize(value)
        over.requirePositive("方向过渡时长")
        val ticks = over.requireWholeTicks("方向过渡时长")
        group.setVariableInterpolated("${name}_x", unit.x.toString(), ticks, easing.toEasingType())
        group.setVariableInterpolated("${name}_y", unit.y.toString(), ticks, easing.toEasingType())
        group.setVariableInterpolated("${name}_z", unit.z.toString(), ticks, easing.toEasingType())
        return this
    }

    private fun normalize(value: Vec3): Vec3 {
        require(value.lengthSqr() > 1e-12) { "方向参数不能是零向量" }
        return value.normalize()
    }
}

/** 标量参数句柄。 */
class ScalarParameter internal constructor(
    private val group: ParticleGroup,
    val name: String,
) {

    val value: Scalar get() = Scalar(name)

    fun set(value: Double): ScalarParameter {
        require(value.isFinite()) { "标量参数必须是有限值" }
        group.setVariableLive(name, value.toString())
        return this
    }

    fun set(value: Double, over: Time, easing: Ease = Ease.LINEAR): ScalarParameter {
        require(value.isFinite()) { "标量参数必须是有限值" }
        over.requirePositive("参数过渡时长")
        group.setVariableInterpolated(name, value.toString(), over.requireWholeTicks("参数过渡时长"), easing.toEasingType())
        return this
    }
}

fun ParticleGroup.vectorParameter(name: String, value: Vec3): VectorParameter {
    registerParameter(name, "vector", "blocks", value)
    return VectorParameter(this, name)
}

fun ParticleGroup.directionParameter(name: String, value: Vec3): DirectionParameter {
    require(value.lengthSqr() > 1e-12) { "方向参数不能是零向量" }
    val unit = value.normalize()
    registerParameter(name, "direction", "unit", unit)
    return DirectionParameter(this, name)
}

fun ParticleGroup.scalarParameter(name: String, value: Double): ScalarParameter {
    require(value.isFinite()) { "标量参数必须是有限值" }
    requireParameterName(name)
    readableParameterNames.add(name)
    readableParameters.add(ParameterInfo(name, "scalar", "-"))
    setVariable(name, value)
    return ScalarParameter(this, name)
}

/** 表达式里的成员信息：索引、数量与确定性随机。 */
class ExpressionMember internal constructor() {

    val index: Scalar get() = Scalar("i")
    val count: Scalar get() = Scalar("n")

    /**
     * 按成员索引、种子与 salt 生成的确定性 [0,1) 随机值。
     *
     * 同一配方在暂停、回绕与重复求值时保持同一值；salt 生成稳定哈希，不依赖 JVM 的随机源。
     */
    @JvmOverloads
    fun random01(seed: Long = 0L, salt: String = ""): Scalar {
        val saltHash = stableHash(salt)
        return Scalar("rand(i*12.9898+(${seed}.0)+(${saltHash}.0))")
    }

    private fun stableHash(text: String): Long {
        var hash = 1469598103934665603L
        for (c in text) {
            hash = hash xor c.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }
}

/** 实体的被动输入；需要先用 `ParticleGroup.defineEntity` 登记该句柄。 */
class ExpressionEntity internal constructor(private val handle: String) {

    private fun value(prop: EntityProp): Scalar = Scalar(prop.call(handle))

    val x: Length get() = Length(Double.NaN, EntityProp.X.call(handle), false)
    val y: Length get() = Length(Double.NaN, EntityProp.Y.call(handle), false)
    val z: Length get() = Length(Double.NaN, EntityProp.Z.call(handle), false)
    val pos: Vector get() = Vector(x, y, z)
    val exists: Scalar get() = value(EntityProp.EXISTS)
    val yaw: Angle get() = Angle(Double.NaN, "(${EntityProp.YAW.call(handle)}*PI/180)", false)
    val pitch: Angle get() = Angle(Double.NaN, "(${EntityProp.PITCH.call(handle)}*PI/180)", false)
    val direction: Direction
        get() = Direction(
            Vector(
                Length(Double.NaN, EntityProp.DIR_X.call(handle), false),
                Length(Double.NaN, EntityProp.DIR_Y.call(handle), false),
                Length(Double.NaN, EntityProp.DIR_Z.call(handle), false),
            )
        )
    val velocity: Vector
        get() = Vector(
            Length(Double.NaN, EntityProp.VEL_X.call(handle), false),
            Length(Double.NaN, EntityProp.VEL_Y.call(handle), false),
            Length(Double.NaN, EntityProp.VEL_Z.call(handle), false),
        )
    val hp: Scalar get() = value(EntityProp.HP)
    val maxHp: Scalar get() = value(EntityProp.HP_MAX)
    val ground: Scalar get() = value(EntityProp.GROUND)
    val onFire: Scalar get() = value(EntityProp.ON_FIRE)
    val sneaking: Scalar get() = value(EntityProp.SNEAKING)
    val swimming: Scalar get() = value(EntityProp.SWIMMING)
    val sprinting: Scalar get() = value(EntityProp.SPRINTING)
}

/** 表达式输出位置：必须显式选择世界坐标或固定轴心的局部坐标。 */
class ExpressionPosition internal constructor(private val frame: ExpressionFrame) {

    fun world(value: Vector) {
        frame.builder.positionCode =
            "x=${value.x.render()};y=${value.y.render()};z=${value.z.render()};"
    }

    fun local(value: Vector) {
        val group = requireNotNull(frame.group) { "没有粒子组时不能构建局部坐标" }
        require(!group.dynamicPivot) {
            "动态轴心（实体/可移动锚点）的局部坐标还不能由标量表达式保留；请改用世界坐标或固定轴心"
        }
        val pivot = group.pivot
        frame.builder.positionCode =
            "x=(${pivot.x}+${value.x.render()});y=(${pivot.y}+${value.y.render()});z=(${pivot.z}+${value.z.render()});"
    }

    /** 当前后端不能保留逐粒子初始位置；显式拒绝，不偷偷补世界原点。 */
    fun preserve(): Nothing = throw UnsupportedOperationException(
        "标量表达式后端不能保留逐粒子位置；请显式使用 position.world 或 position.local",
    )
}

/** 表达式构建帧。 */
class ExpressionFrame internal constructor(
    internal val group: ParticleGroup?,
    internal val builder: TypedExpressionBuilder,
) {

    /** 显式命名入口：`{ frame.progress(...) }` 与接收者写法等价。 */
    val frame: ExpressionFrame get() = this

    val member: ExpressionMember = ExpressionMember()
    val position: ExpressionPosition = ExpressionPosition(this)

    /** 组年龄，单位毫秒；只在构建表达式文本时使用。 */
    val age: Time get() = Time(Double.NaN, "t", false)

    /** 0..1 进度；duration 必须是正的常量时间。 */
    fun progress(duration: Time): Scalar {
        duration.requirePositive("progress 时长")
        return clamp01(Scalar("(t/${duration.render()})"))
    }

    fun scale(value: Scalar) {
        builder.scaleCode = "sc=${value.expr};"
    }

    fun color(color: Color) {
        builder.colorCode = "r=${color.r};g=${color.g};b=${color.b};a=${color.a};"
    }

    fun entity(handle: String): ExpressionEntity {
        require(PARAM_NAME.matches(handle)) { "实体句柄 '$handle' 不是合法标识符" }
        group?.let { require(it.hasEntityHandle(handle)) { "实体句柄 '$handle' 尚未在组上 defineEntity" } }
        return ExpressionEntity(handle)
    }

    fun world(prop: WorldProp): Scalar = Scalar(prop.call())
}

internal class TypedExpressionBuilder {
    var positionCode: String? = null
    var scaleCode: String? = null
    var colorCode: String? = null

    fun build(): String {
        val position = positionCode
            ?: throw IllegalArgumentException("类型化表达式必须显式设置 position.world 或 position.local")
        return position + (scaleCode ?: "") + (colorCode ?: "")
    }
}

/** 构建结果，可用于调试导出或稍后应用。 */
class TypedExpression internal constructor(
    val group: ParticleGroup,
    val code: String,
    val durationTicks: Int,
    val parameters: List<ParameterInfo>,
) {

    fun apply(): ParticleGroup {
        group.expression(code, durationTicks)
        return group
    }

    fun debugString(): String = buildString {
        appendLine("backend=group-scalar")
        appendLine("durationTicks=$durationTicks")
        for (p in parameters) appendLine("parameter ${p.name}: ${p.kind} (${p.unit})")
        append(code)
    }

    override fun toString(): String = code
}

/**
 * Java 友好的表达式文本构建器：只覆盖位置/缩放/颜色三种输出。
 *
 * 复杂参数、实体输入与进度请用 Kotlin DSL；Java 仍可直接调用 `ParticleGroup.expression`。
 */
class ExpressionTextBuilder internal constructor(duration: Time) {

    private val builder = TypedExpressionBuilder()
    val durationTicks: Int = duration.requireWholeTicks("表达式时长")

    fun positionWorld(x: Scalar, y: Scalar, z: Scalar): ExpressionTextBuilder {
        builder.positionCode = "x=${x.expr};y=${y.expr};z=${z.expr};"
        return this
    }

    fun positionWorld(x: Length, y: Length, z: Length): ExpressionTextBuilder =
        positionWorld(x.toScalar(), y.toScalar(), z.toScalar())

    fun scale(value: Scalar): ExpressionTextBuilder {
        builder.scaleCode = "sc=${value.expr};"
        return this
    }

    fun color(color: Color): ExpressionTextBuilder {
        builder.colorCode = "r=${color.r};g=${color.g};b=${color.b};a=${color.a};"
        return this
    }

    fun buildCode(): String = builder.build()

    fun apply(group: ParticleGroup): ParticleGroup = group.expression(buildCode(), durationTicks)
}

object Expressions {
    @JvmStatic
    fun builder(duration: Time): ExpressionTextBuilder = ExpressionTextBuilder(duration)
}

/**
 * 构建类型化表达式但不立即下发；用于调试导出和先校验再应用。
 */
fun ParticleGroup.buildExpression(
    duration: Time,
    block: ExpressionFrame.() -> Unit,
): TypedExpression {
    val ticks = duration.requireWholeTicks("表达式时长")
    val builder = TypedExpressionBuilder()
    val frame = ExpressionFrame(this, builder)
    frame.block()
    return TypedExpression(this, builder.build(), ticks, readableParameters.toList())
}

/**
 * 类型化表达式入口：构建一次，生成既有标量表达式文本并交给 [ParticleGroup.expression]。
 *
 * 与字符串表达式、函数对象脚本和 UV 表达式不是同一套后端；这里只覆盖组标量表达式能表达的
 * 位置、颜色与缩放。需要其它通道时请继续使用底层字符串 API。
 */
fun ParticleGroup.expression(
    duration: Time,
    block: ExpressionFrame.() -> Unit,
): ParticleGroup = buildExpression(duration, block).apply()

/** 固定/常量表。 */
object Angles {
    /** 黄金角（弧度），用于稳定的成员分布。 */
    @JvmField val goldenAngle: Scalar = Scalar.of(2.399963229728653)

    @JvmField val PI_SCALAR: Scalar = Scalar.of(PI)
}
