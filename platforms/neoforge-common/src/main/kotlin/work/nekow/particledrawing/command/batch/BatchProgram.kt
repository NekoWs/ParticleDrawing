package work.nekow.particledrawing.command.batch

import net.minecraft.commands.CommandSourceStack
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.morph.MorphAllocation
import work.nekow.particledrawing.animation.morph.MorphEasing
import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphPath
import work.nekow.particledrawing.animation.morph.MorphSlotFormulas
import work.nekow.particledrawing.animation.morph.MorphWobble
import work.nekow.particledrawing.animation.recipe.GroupExpressionOutputs
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.failureComponent
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import java.util.UUID


/**
 * `kind=program` 的组时间线：把 `program.<序号>=动作,参数...` 解析成动作对象，
 * 校验顺序与前置状态后再按序录制；`morph.*` 与 `program.N=morph.start` 组合触发形变。
 */
internal object BatchProgram {

    data class Plan(
        var keep: Boolean = false,
        val actions: MutableList<Action> = arrayListOf(),
        var morph: MorphConfig? = null,
        var morphTouched: Boolean = false,
    )

    /** 形变参数，语义与 `/pdraw particle program <组> morph ...` 一致。 */
    data class MorphConfig(
        var slotCount: Int = 16,
        var position: String = "",
        var yaw: String? = null,
        var pitch: String? = null,
        var size: String? = null,
        var control1: String? = null,
        var control2: String? = null,
        var ticks: Int = 40,
        var stagger: Int = 0,
        var loop: Int = 0,
        var maxSpeed: Double = 0.0,
        var maxAccel: Double = 0.0,
        var converge: Double = 0.05,
        var path: MorphPath = MorphPath.STRAIGHT,
        var easing: String = "linear",
        var wobble: MorphWobble = MorphWobble.NONE,
        var wobbleStrength: Double = 0.0,
        var wobblePeaks: Double = 1.0,
        var allocation: MorphAllocation = MorphAllocation.NEAREST,
        var dynamic: Boolean = false,
        var keepVelocity: Boolean = false,
        var attributes: Boolean? = null,
    ) {
        fun options(): MorphOptions = MorphOptions(
            ticks = ticks,
            staggerTicks = stagger,
            maxSpeed = maxSpeed,
            maxAccel = maxAccel,
            path = path,
            easing = MorphEasing.byName(easing) ?: MorphEasing.LINEAR,
            wobble = wobble,
            wobbleStrength = wobbleStrength,
            wobblePeaks = wobblePeaks,
            convergeEps = if (converge <= 0.0) 0.05 else converge,
            loop = loop,
            allocation = allocation,
            keepVelocity = keepVelocity,
            writeAttributes = attributes ?: (yaw != null || pitch != null || size != null),
        )
    }

    sealed class Action(val activates: Boolean, val requiresActive: Boolean = false) {
        data class Delay(val ticks: Int) : Action(true)
        data class Move(val delta: Vec3, val ticks: Int, val easing: EasingType) : Action(true)
        data class MoveOffset(val ratio: Float, val ticks: Int, val easing: EasingType) : Action(true)
        data class MovePath(val points: List<Vec3>, val ticks: Int, val easing: EasingType) : Action(true)
        data class Rotate(val axis: Vec3, val degrees: Double, val ticks: Int, val easing: EasingType) : Action(true)
        data class Recolor(val color: work.nekow.particledrawing.api.Color, val ticks: Int, val easing: EasingType) : Action(true)
        data class ScaleBy(val ratio: Float, val ticks: Int, val easing: EasingType) : Action(true)
        data class ScaleTo(val ratio: Float, val ticks: Int, val easing: EasingType) : Action(true)
        data class FadeIn(val ticks: Int, val easing: EasingType) : Action(true)
        data class FadeOut(val ticks: Int, val keep: Boolean, val easing: EasingType) : Action(true)
        data class Spin(val axis: Vec3, val degreesPerTick: Double) : Action(true)
        data class Pulse(val peak: Float, val halfPeriod: Int, val cycles: Int) : Action(true)
        data class Pivot(val position: Vec3) : Action(true)
        data class Follow(val entity: UUID, val offset: Vec3, val local: Boolean) : Action(true)
        data class AnchorEntity(val entity: UUID, val offset: Vec3, val local: Boolean) : Action(true)
        data class VariableSet(val name: String, val value: Double) : Action(false)
        data class VariableLive(val name: String, val formula: String) : Action(true, requiresActive = true)
        data class VariableEase(val name: String, val value: String, val ticks: Int, val easing: EasingType) : Action(true, requiresActive = true)
        data class Expression(val ticks: Int, val code: String, val local: Boolean) : Action(true)
        object StopContinuous : Action(true, requiresActive = true)
        data class DestroyAfter(val ticks: Int) : Action(true)
        data class Retire(val grace: Int) : Action(true, requiresActive = true)
        data class Entity(val handle: String, val entity: UUID) : Action(false)
        object MorphStart : Action(true)
        object MorphReset : Action(true)
    }

    private val FORMULA_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val RESERVED_NAMES = setOf(
        "i", "n", "t", "PI", "E", "x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light",
    )

    fun parse(tokens: List<BatchToken>, source: CommandSourceStack): Plan {
        val plan = Plan()
        val indices = ArrayList<Int>()
        val morph = MorphConfig()
        var morphTouched = false
        val seen = HashMap<String, Int>()
        for (token in tokens) {
            val spec = BatchKeys.require(token.key, BatchKind.PROGRAM)
            if (!spec.repeatable) {
                val count = seen.merge(token.key, 1, Int::plus) ?: 1
                requireLocalized(count <= 1) { translation("error.batch.duplicate_key", token.key) }
            }
            when {
                token.key == "kind" || token.key == "dryrun" -> Unit
                token.key == "keep" -> plan.keep = BatchOptions.bool(token.value, token.key)
                token.key.startsWith("program.") -> {
                    val index = token.key.removePrefix("program.").toIntOrNull()
                        ?: throw LocalizedArgumentException(translation("error.batch.program_index", token.key))
                    requireLocalized(index >= 0) { translation("error.batch.program_index", token.key) }
                    indices += index
                    plan.actions += action(index, token.value, source)
                }
                token.key.startsWith("morph.") -> {
                    morphTouched = true
                    morph(token.key, token.value, morph)
                }
                else -> throw LocalizedArgumentException(translation("error.batch.unknown_key", token.key, "program"))
            }
        }
        requireLocalized(indices.size == indices.distinct().size) {
            val duplicate = indices.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.first()
            translation("error.batch.program_duplicate", duplicate)
        }
        requireLocalized(plan.actions.isNotEmpty()) { translation("error.batch.program_empty") }
        val usesMorphStart = plan.actions.any { it is Action.MorphStart }
        if (usesMorphStart) {
            requireLocalized(morph.position.isNotBlank()) { translation("error.morph.slots_command") }
        } else {
            requireLocalized(!morphTouched) { translation("error.batch.morph_without_start") }
        }
        if (morphTouched || usesMorphStart) {
            // 用一次真实求值验证公式，失败时不落地。
            MorphSlotFormulas(morph.slotCount, morph.position, morph.yaw, morph.pitch, morph.size, morph.control1, morph.control2)
                .build(0.0)
            plan.morph = morph
        }
        plan.morphTouched = morphTouched
        return plan
    }

    private fun morph(key: String, value: String, config: MorphConfig) {
        val raw = BatchOptions.text(value)
        when (key) {
            "morph.slots" -> {
                val parts = BatchOptions.splitList(value)
                BatchOptions.requireCountAtLeast(parts, 2, key)
                config.slotCount = BatchOptions.int(parts[0], key)
                config.position = BatchOptions.formula(parts.drop(1).joinToString(","))
            }
            "morph.yaw" -> config.yaw = BatchOptions.formula(value)
            "morph.pitch" -> config.pitch = BatchOptions.formula(value)
            "morph.size" -> config.size = BatchOptions.formula(value)
            "morph.control1" -> config.control1 = BatchOptions.formula(value)
            "morph.control2" -> config.control2 = BatchOptions.formula(value)
            "morph.ticks" -> config.ticks = BatchOptions.int(raw, key)
            "morph.stagger" -> config.stagger = BatchOptions.int(raw, key)
            "morph.loop" -> config.loop = BatchOptions.int(raw, key)
            "morph.max_speed" -> config.maxSpeed = BatchOptions.double(raw, key)
            "morph.max_accel" -> config.maxAccel = BatchOptions.double(raw, key)
            "morph.converge" -> config.converge = BatchOptions.double(raw, key)
            "morph.path" -> config.path = when (BatchOptions.value(BatchToken(key, raw), listOf("straight", "quadratic", "cubic"))) {
                "quadratic" -> MorphPath.QUADRATIC
                "cubic" -> MorphPath.CUBIC
                else -> MorphPath.STRAIGHT
            }
            "morph.easing" -> {
                requireLocalized(MorphEasing.byName(raw) != null) { translation("error.easing.unknown", raw) }
                config.easing = raw
            }
            "morph.wobble" -> {
                val parts = BatchOptions.splitList(value)
                config.wobble = when (BatchOptions.text(parts[0]).lowercase()) {
                    "none" -> MorphWobble.NONE
                    "bell" -> MorphWobble.BELL
                    "noise" -> MorphWobble.NOISE
                    "vortex" -> MorphWobble.VORTEX
                    else -> throw LocalizedArgumentException(translation("error.morph.wobble"))
                }
                if (parts.size > 1) config.wobbleStrength = BatchOptions.double(parts[1], key)
                if (parts.size > 2) config.wobblePeaks = BatchOptions.double(parts[2], key)
            }
            "morph.allocation" -> config.allocation = when (BatchOptions.value(BatchToken(key, raw), listOf("nearest", "index", "angle"))) {
                "index" -> MorphAllocation.INDEX
                "angle" -> MorphAllocation.ANGLE
                else -> MorphAllocation.NEAREST
            }
            "morph.dynamic" -> config.dynamic = BatchOptions.bool(raw, key)
            "morph.keep_velocity" -> config.keepVelocity = BatchOptions.bool(raw, key)
            "morph.attributes" -> config.attributes = BatchOptions.bool(raw, key)
            else -> throw LocalizedArgumentException(translation("error.batch.unknown_key", key, "program"))
        }
    }

    private fun action(index: Int, raw: String, source: CommandSourceStack): Action {
        val parts = BatchOptions.splitList(raw)
        val name = BatchOptions.text(parts[0]).lowercase()
        requireLocalized(name in BatchKeys.actionArgs) { translation("error.batch.action", name, index) }
        fun arg(at: Int): String {
            requireLocalized(at < parts.size) { translation("error.batch.action_args", name, BatchKeys.actionArgs.getValue(name)) }
            return BatchOptions.text(parts[at])
        }
        fun optional(at: Int): String? = parts.getOrNull(at)?.let { BatchOptions.text(it) }
        fun easing(at: Int, default: EasingType): EasingType =
            optional(at)?.let { BatchOptions.easing(it, "$name.$at") } ?: default
        fun ticks(at: Int): Int = BatchOptions.int(arg(at), "$name.$at")
        fun double(at: Int): Double = BatchOptions.double(arg(at), "$name.$at")
        fun vector(at: Int): Vec3 = Vec3(double(at), double(at + 1), double(at + 2))
        fun entity(rawSelector: String): UUID = BatchEmitter.resolveEntity(rawSelector, source.level).uuid
        fun formula(at: Int): String = BatchOptions.formula(parts[at])
        return when (name) {
            "delay" -> Action.Delay(ticks(1))
            "move" -> Action.Move(vector(1), ticks(4), easing(5, EasingType.LINEAR))
            "move_offset" -> Action.MoveOffset(double(1).toFloat(), ticks(2), easing(3, EasingType.LINEAR))
            "move_path" -> Action.MovePath(parsePath(arg(2)), ticks(1), easing(3, EasingType.LINEAR))
            "rotate" -> Action.Rotate(vector(1), double(4), ticks(5), easing(6, EasingType.LINEAR))
            "recolor" -> Action.Recolor(ParticleCommandOptionParser.parseColor(arg(1)), ticks(2), easing(3, EasingType.LINEAR))
            "scale_by" -> Action.ScaleBy(double(1).toFloat(), ticks(2), easing(3, EasingType.LINEAR))
            "scale_to" -> Action.ScaleTo(double(1).toFloat(), ticks(2), easing(3, EasingType.LINEAR))
            "fade_in" -> Action.FadeIn(ticks(1), easing(2, EasingType.EASE_OUT))
            "fade_out" -> Action.FadeOut(ticks(1), keep = false, easing(2, EasingType.EASE_IN))
            "fade_out_keep" -> Action.FadeOut(ticks(1), keep = true, easing(2, EasingType.EASE_IN))
            "spin" -> Action.Spin(vector(1), double(4))
            "pulse" -> Action.Pulse(double(1).toFloat(), ticks(2), optional(3)?.let { BatchOptions.int(it, "$name.3") } ?: -1)
            "pivot" -> Action.Pivot(vector(1))
            "follow" -> Action.Follow(
                entity(arg(1)),
                optional(2)?.let { _ -> Vec3(double(2), double(3), double(4)) } ?: Vec3.ZERO,
                optional(5)?.let { BatchOptions.bool(it, "follow.local") } ?: false,
            )
            "anchor.fixed" -> Action.Pivot(vector(1))
            "anchor.entity" -> Action.AnchorEntity(
                entity(arg(1)),
                optional(2)?.let { _ -> Vec3(double(2), double(3), double(4)) } ?: Vec3.ZERO,
                optional(5)?.let { BatchOptions.bool(it, "anchor.local") } ?: false,
            )
            "variable.set" -> Action.VariableSet(requireName(arg(1)), double(2))
            "variable.live" -> Action.VariableLive(requireName(arg(1)), formula(2))
            "variable.ease" -> Action.VariableEase(requireName(arg(1)), arg(2), ticks(3), easing(4, EasingType.LINEAR))
            "expression" -> {
                val code = formula(2)
                GroupExpressionOutputs.validate(code)
                Action.Expression(ticks(1), code, local = false)
            }
            "expression.local" -> {
                val code = formula(2)
                GroupExpressionOutputs.validate(code)
                Action.Expression(ticks(1), code, local = true)
            }
            "stop_continuous" -> Action.StopContinuous
            "destroy_after" -> Action.DestroyAfter(ticks(1))
            "retire" -> Action.Retire(optional(1)?.let { BatchOptions.int(it, "retire.grace") } ?: 2)
            "entity" -> Action.Entity(requireName(arg(1)), entity(arg(2)))
            "morph.start" -> Action.MorphStart
            else -> Action.MorphReset
        }
    }

    private fun requireName(name: String): String {
        requireLocalized(FORMULA_NAME.matches(name)) { translation("error.command.identifier", "name", name) }
        requireLocalized(name !in RESERVED_NAMES) { translation("error.command.identifier_reserved", name) }
        return name
    }

    private fun parsePath(raw: String): List<Vec3> = raw.split(';').mapIndexed { index, point ->
        val parts = point.split(',')
        requireLocalized(parts.size == 3) { translation("error.command.path_point", index + 1) }
        val values = parts.map { it.trim().toDoubleOrNull()?.takeIf(Double::isFinite) }
        requireLocalized(values.all { it != null }) { translation("error.command.path_number", index + 1) }
        Vec3(values[0]!!, values[1]!!, values[2]!!)
    }

    /** 校验：时间线前置状态、句柄/变量冲突与形变公式；不写任何东西。 */
    fun validate(plan: Plan, dimension: UUID, name: String) {
        val existing = CommandParticleGroups.get(dimension, name)
        if (!plan.keep && existing != null && existing.group.hasDeliveredProgram()) {
            throw LocalizedArgumentException(translation("error.batch.program_delivered", name))
        }
        var active = existing?.activated ?: false
        var morphStarted = false
        val handles = hashSetOf<String>()
        val vars = hashSetOf<String>()
        for (action in plan.actions) {
            requireLocalized(!action.requiresActive || active) { translation("error.group.not_started", name) }
            requireLocalized(action.activates || !active) { translation("error.group.static_declarations") }
            if (action.activates) active = true
            when (action) {
                is Action.Entity -> {
                    requireLocalized(
                        action.handle !in handles && action.handle !in vars &&
                            existing?.group?.hasEntityHandle(action.handle) != true,
                    ) { translation("error.group.handle_taken", action.handle) }
                    handles += action.handle
                }
                is Action.VariableSet -> {
                    requireLocalized(action.name !in vars && action.name !in handles) { translation("error.group.handle_taken", action.name) }
                    vars += action.name
                }
                is Action.VariableLive -> {
                    requireLocalized(action.name !in vars && action.name !in handles) { translation("error.group.handle_taken", action.name) }
                    vars += action.name
                }
                is Action.VariableEase -> {
                    requireLocalized(action.name !in vars && action.name !in handles) { translation("error.group.handle_taken", action.name) }
                    vars += action.name
                }
                is Action.MorphStart -> {
                    requireLocalized(!plan.actions.takeWhile { it !== action }.any { it is Action.Expression }) {
                        translation("error.group.morph_position")
                    }
                    morphStarted = true
                }
                else -> Unit
            }
        }
        requireLocalized(!morphStarted || plan.morph != null) { translation("error.batch.morph_without_start") }
        plan.morph?.let { morph ->
            MorphSlotFormulas(morph.slotCount, morph.position, morph.yaw, morph.pitch, morph.size, morph.control1, morph.control2).build(0.0)
            morph.options()
        }
    }

    /** 落地；返回 Action 数量。 */
    fun commit(plan: Plan, dimension: UUID, name: String, source: CommandSourceStack): Int {
        val manager = work.nekow.particledrawing.api.ParticleManager.of(source.level)
        val entry = CommandParticleGroups.get(dimension, name)
            ?: CommandParticleGroups.create(dimension, name, manager.createGroup(source.position))
        if (!plan.keep) requireLocalized(entry.group.resetRecordedProgram()) { translation("error.batch.program_delivered", name) }
        entry.programmed = true
        if (plan.actions.any { it.activates }) entry.activated = true
        fun apply() {
            try {
                for (action in plan.actions) applyAction(entry.group, action, plan)
            } catch (error: Exception) {
                source.sendFailure(error.failureComponent("error.command.program_parameters"))
            }
        }
        if (CommandParticleGroups.hasPendingMembers(entry)) {
            CommandParticleGroups.runWhenMembersReady(dimension, entry) { apply() }
        } else {
            apply()
        }
        return plan.actions.size
    }

    private fun applyAction(group: ParticleGroup, action: Action, plan: Plan) {
        when (action) {
            is Action.Delay -> group.delay(action.ticks)
            is Action.Move -> group.move(action.delta, action.ticks, action.easing)
            is Action.MoveOffset -> group.moveAlongOffset(action.ratio, action.ticks, action.easing)
            is Action.MovePath -> group.movePath(action.points, action.ticks, action.easing)
            is Action.Rotate -> group.rotate(action.axis, Math.toRadians(action.degrees), action.ticks, action.easing)
            is Action.Recolor -> group.recolor(action.color, action.ticks, action.easing)
            is Action.ScaleBy -> group.scaleBy(action.ratio, action.ticks, action.easing)
            is Action.ScaleTo -> group.scaleTo(action.ratio, action.ticks, action.easing)
            is Action.FadeIn -> group.fadeIn(action.ticks, action.easing)
            is Action.FadeOut -> group.fadeOut(action.ticks, removeAfter = !action.keep, easing = action.easing)
            is Action.Spin -> group.spin(action.axis, Math.toRadians(action.degreesPerTick))
            is Action.Pulse -> group.pulse(action.peak, action.halfPeriod, action.cycles)
            is Action.Pivot -> group.setPivot(action.position)
            is Action.Follow -> group.followEntity(action.entity, action.offset, action.local)
            is Action.AnchorEntity -> group.followEntity(action.entity, action.offset, action.local)
            is Action.VariableSet -> group.setVariable(action.name, action.value)
            is Action.VariableLive -> group.setVariableLive(action.name, action.formula)
            is Action.VariableEase -> group.setVariableInterpolated(action.name, action.value, action.ticks, action.easing)
            is Action.Expression -> if (action.local) group.expressionLocal(action.code, action.ticks) else group.expression(action.code, action.ticks)
            is Action.StopContinuous -> group.stopContinuous()
            is Action.DestroyAfter -> group.destroyAfter(action.ticks)
            is Action.Retire -> group.retire(action.grace)
            is Action.Entity -> group.defineEntity(action.handle, action.entity)
            is Action.MorphStart -> {
                val morph = requireNotNullLocalized(plan.morph) { translation("error.batch.morph_without_start") }
                group.morph(
                    morph.slotCount, morph.position, morph.options(),
                    morph.yaw, morph.pitch, morph.size, morph.control1, morph.control2, morph.dynamic,
                )
            }
            is Action.MorphReset -> Unit
        }
    }
}


