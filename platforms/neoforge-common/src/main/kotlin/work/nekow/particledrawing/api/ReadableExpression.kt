package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3

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
        group.setVariableLive("${name}_x", numberLiteral(value.x))
        group.setVariableLive("${name}_y", numberLiteral(value.y))
        group.setVariableLive("${name}_z", numberLiteral(value.z))
        return this
    }

    fun set(value: Vec3, over: Time, easing: Ease = Ease.LINEAR): VectorParameter {
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) { "向量参数必须是有限值" }
        over.requirePositive("参数过渡时长")
        val ticks = over.requireWholeTicks("参数过渡时长")
        group.setVariableInterpolated("${name}_x", numberLiteral(value.x), ticks, easing.toEasingType())
        group.setVariableInterpolated("${name}_y", numberLiteral(value.y), ticks, easing.toEasingType())
        group.setVariableInterpolated("${name}_z", numberLiteral(value.z), ticks, easing.toEasingType())
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
        group.setVariableLive("${name}_x", numberLiteral(unit.x))
        group.setVariableLive("${name}_y", numberLiteral(unit.y))
        group.setVariableLive("${name}_z", numberLiteral(unit.z))
        return this
    }

    fun set(value: Vec3, over: Time, easing: Ease = Ease.LINEAR): DirectionParameter {
        val unit = normalize(value)
        over.requirePositive("方向过渡时长")
        val ticks = over.requireWholeTicks("方向过渡时长")
        group.setVariableInterpolated("${name}_x", numberLiteral(unit.x), ticks, easing.toEasingType())
        group.setVariableInterpolated("${name}_y", numberLiteral(unit.y), ticks, easing.toEasingType())
        group.setVariableInterpolated("${name}_z", numberLiteral(unit.z), ticks, easing.toEasingType())
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
        group.setVariableLive(name, numberLiteral(value))
        return this
    }

    fun set(value: Double, over: Time, easing: Ease = Ease.LINEAR): ScalarParameter {
        require(value.isFinite()) { "标量参数必须是有限值" }
        over.requirePositive("参数过渡时长")
        group.setVariableInterpolated(name, numberLiteral(value), over.requireWholeTicks("参数过渡时长"), easing.toEasingType())
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

/**
 * 组原生可更新阶段句柄。
 *
 * 变量只保存起点、时长与状态，进度仍由表达式 `t` 逐帧计算；更新一次后暂停/插值都由现有后端处理。
 * 状态 0 = 未开始，1 = 运行，2 = 完成。
 */
/** 阶段表达式片段；与句柄分离，便于用既有求值器逐点核对。 */
internal fun phaseStartedExpr(name: String): Scalar =
    step(Scalar.of(1.0), Scalar("${name}_state"))

internal fun phaseCompletedExpr(name: String): Scalar =
    step(Scalar.of(2.0), Scalar("${name}_state"))

internal fun phaseProgressExpr(name: String): Scalar {
    val active = phaseStartedExpr(name) - phaseCompletedExpr(name)
    val progress = clamp01(Scalar("((t-${name}_start)/${name}_duration)"))
    return progress * active + phaseCompletedExpr(name)
}

class PhaseParameter internal constructor(
    private val group: ParticleGroup,
    val name: String,
) {

    private val startName = "${name}_start"
    private val durationName = "${name}_duration"
    private val stateName = "${name}_state"
    private var state = 0

    /** 0..1；未开始为 0，完成后为 1。 */
    val progress: Scalar = phaseProgressExpr(name)

    val started: Scalar get() = phaseStartedExpr(name)
    val completed: Scalar get() = phaseCompletedExpr(name)
    val active: Scalar get() = started - completed
    val remaining: Scalar get() = Scalar.of(1.0) - progress
    val duration: Scalar get() = Scalar(durationName)

    /** 是否正在运行（服务端句柄状态，不是表达式结果）。 */
    val isActive: Boolean get() = state == 1

    internal fun register() {
        require(PARAM_NAME.matches(name)) { "阶段名 '$name' 不是合法标识符" }
        require(name.length + "_duration".length <= 64) { "阶段名 '$name' 过长" }
        require(name !in RESERVED_PARAM_NAMES) { "阶段名 '$name' 与内建名字冲突" }
        for (variable in listOf(startName, durationName, stateName)) {
            require(variable !in group.readableParameterNames) { "阶段变量 '$variable' 已经绑定过" }
        }
        group.readableParameterNames.add(startName)
        group.readableParameterNames.add(durationName)
        group.readableParameterNames.add(stateName)
        group.readableParameters.add(ParameterInfo(name, "phase", "ms/state"))
        group.setVariable(startName, 0.0)
        group.setVariable(durationName, 1.0)
        group.setVariable(stateName, 0.0)
    }

    /** 从当前表达式时刻开始计时；重复调用即重入。 */
    fun start(duration: Time): PhaseParameter {
        duration.requirePositive("阶段时长")
        val ms = duration.millis
        require(ms.isFinite() && ms > 0.0) { "阶段时长必须是正的有限值" }
        val now = group.currentExpressionTimeMs()
        set("_state", 0.0)
        set("_start", now.toDouble())
        set("_duration", ms)
        set("_state", 1.0)
        state = 1
        return this
    }

    fun restart(duration: Time): PhaseParameter = start(duration)

    /** 提前结束：进度直接落到 1，之后保持完成状态。 */
    fun complete() {
        set("_state", 2.0)
        state = 2
    }

    fun finish() = complete()

    fun reset() {
        set("_state", 0.0)
        set("_start", 0.0)
        set("_duration", 1.0)
        set("_state", 0.0)
        state = 0
    }

    private fun set(suffix: String, value: Double) {
        val variable = "$name$suffix"
        group.setVariable(variable, value)
        group.setVariableLive(variable, numberLiteral(value))
    }
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

/** 表达式输出位置：显式选择世界坐标或当前组锚点的局部坐标。 */
class ExpressionPosition internal constructor(private val frame: ExpressionFrame) {

    fun world(value: Vector) {
        frame.builder.localPosition = false
        frame.builder.positionCode =
            "x=${value.x.render()};y=${value.y.render()};z=${value.z.render()};"
    }

    fun local(value: Vector) {
        requireNotNull(frame.group) { "没有粒子组时不能构建局部坐标" }
        frame.builder.localPosition = true
        frame.builder.positionCode =
            "x=${value.x.render()};y=${value.y.render()};z=${value.z.render()};"
    }

    /** 当前后端不能保留逐粒子初始位置；显式拒绝，不偷偷补世界原点。 */
    fun preserve(): Nothing = throw UnsupportedOperationException(
        "标量表达式后端不能保留逐粒子位置；请显式使用 position.world 或 position.local",
    )
}

/**
 * 组时间轴上的命名阶段：进度、已开始、已完成都直接读表达式时间 `t`。
 *
 * 不引入独立时钟；同一表达式里的多个阶段共享同一 `t`，因此无需手工 `clockOffset`。
 */
class Phase internal constructor(
    val duration: Time,
    val offset: Time,
) {

    val progress: Scalar = clamp01(
        Scalar("((t-${offset.render()})/${duration.render()})")
    )

    val started: Scalar = step(
        Scalar.of(0.0),
        Scalar("(t-${offset.render()})"),
    )

    val completed: Scalar = step(
        Scalar(duration.render()),
        Scalar("(t-${offset.render()})"),
    )

    val remaining: Scalar = Scalar.of(1.0) - progress
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
        builder.colorCode =
            "r=${numberLiteral(color.r)};g=${numberLiteral(color.g)};" +
                "b=${numberLiteral(color.b)};a=${numberLiteral(color.a)};"
    }

    /** 动态颜色通道；各分量直接写进现有表达式的 r/g/b/a。 */
    @JvmOverloads
    fun color(red: Scalar, green: Scalar, blue: Scalar, alpha: Scalar = Scalar.of(1.0)) {
        builder.colorCode =
            "r=${red.expr};g=${green.expr};b=${blue.expr};a=${alpha.expr};"
    }

    /**
     * 命名局部表达式：在生成文本开头赋一次值，后续复用变量名。
     *
     * 同名重复绑定、保留名冲突都在构建时拒绝；调试导出会列出局部表达式。
     */
    fun local(name: String, value: Scalar): Scalar {
        require(PARAM_NAME.matches(name)) { "局部表达式名 '$name' 不是合法标识符" }
        require(name !in RESERVED_PARAM_NAMES) { "局部表达式名 '$name' 与内建名字冲突" }
        require(name !in builder.locals) { "局部表达式名 '$name' 重复绑定" }
        group?.let { require(name !in it.readableParameterNames) { "局部表达式名 '$name' 与参数冲突" } }
        builder.locals[name] = value
        return Scalar(name)
    }

    /** 从组年龄 0 起的阶段；[offset] 用于同一时间轴上的延后阶段。 */
    @JvmOverloads
    fun phase(duration: Time, offset: Time = Time.milliseconds(0.0)): Phase {
        duration.requirePositive("阶段时长")
        offset.requireNonNegative("阶段偏移")
        return Phase(duration, offset)
    }

    /** [phase] 的别名，表达“这个阶段从何时开始”。 */
    @JvmOverloads
    fun start(duration: Time, offset: Time = Time.milliseconds(0.0)): Phase = phase(duration, offset)

    fun entity(handle: String): ExpressionEntity {
        require(PARAM_NAME.matches(handle)) { "实体句柄 '$handle' 不是合法标识符" }
        group?.let { require(it.hasEntityHandle(handle)) { "实体句柄 '$handle' 尚未在组上 defineEntity" } }
        return ExpressionEntity(handle)
    }

    fun world(prop: WorldProp): Scalar = Scalar(prop.call())
}

internal class TypedExpressionBuilder {
    var localPosition = false
    var positionCode: String? = null
    var scaleCode: String? = null
    var colorCode: String? = null
    val locals = LinkedHashMap<String, Scalar>()

    fun build(): String {
        val position = positionCode
            ?: throw IllegalArgumentException("类型化表达式必须显式设置 position.world 或 position.local")
        val prelude = buildString {
            for ((name, value) in locals) append("$name=${value.expr};")
        }
        return prelude + position + (scaleCode ?: "") + (colorCode ?: "")
    }
}

/** 构建结果，可用于调试导出或稍后应用。 */
class TypedExpression internal constructor(
    val group: ParticleGroup,
    val code: String,
    val durationTicks: Int,
    val parameters: List<ParameterInfo>,
    val locals: Map<String, String>,
    val localPosition: Boolean = false,
) {

    fun apply(): ParticleGroup {
        if (localPosition) group.expressionLocal(code, durationTicks) else group.expression(code, durationTicks)
        return group
    }

    fun debugString(): String = buildString {
        appendLine("backend=group-scalar")
        appendLine("durationTicks=$durationTicks")
        appendLine("positionSpace=${if (localPosition) "anchor" else "world"}")
        for (p in parameters) appendLine("parameter ${p.name}: ${p.kind} (${p.unit})")
        for ((name, value) in locals) appendLine("local $name = $value")
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
        builder.localPosition = false
        builder.positionCode = "x=${x.expr};y=${y.expr};z=${z.expr};"
        return this
    }

    fun positionWorld(x: Length, y: Length, z: Length): ExpressionTextBuilder =
        positionWorld(x.toScalar(), y.toScalar(), z.toScalar())

    fun positionLocal(x: Scalar, y: Scalar, z: Scalar): ExpressionTextBuilder {
        builder.positionCode = "x=${x.expr};y=${y.expr};z=${z.expr};"
        builder.localPosition = true
        return this
    }

    fun scale(value: Scalar): ExpressionTextBuilder {
        builder.scaleCode = "sc=${value.expr};"
        return this
    }

    fun color(color: Color): ExpressionTextBuilder {
        builder.colorCode =
            "r=${numberLiteral(color.r)};g=${numberLiteral(color.g)};" +
                "b=${numberLiteral(color.b)};a=${numberLiteral(color.a)};"
        return this
    }

    @JvmOverloads
    fun color(red: Scalar, green: Scalar, blue: Scalar, alpha: Scalar = Scalar.of(1.0)): ExpressionTextBuilder {
        builder.colorCode = "r=${red.expr};g=${green.expr};b=${blue.expr};a=${alpha.expr};"
        return this
    }

    fun buildCode(): String = builder.build()

    fun apply(group: ParticleGroup): ParticleGroup =
        if (builder.localPosition) group.expressionLocal(buildCode(), durationTicks) else group.expression(buildCode(), durationTicks)
}

object Expressions {
    @JvmStatic
    fun builder(duration: Time): ExpressionTextBuilder = ExpressionTextBuilder(duration)

    @JvmStatic
    fun phaseParameter(group: ParticleGroup, name: String): PhaseParameter =
        group.phaseParameter(name)
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
    return TypedExpression(
        this,
        builder.build(),
        ticks,
        readableParameters.toList(),
        builder.locals.mapValues { it.value.expr },
        builder.localPosition,
    )
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
