package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.morph.MorphAllocation
import work.nekow.particledrawing.animation.morph.MorphEasing
import work.nekow.particledrawing.animation.morph.MorphPath
import work.nekow.particledrawing.animation.morph.MorphSlotFormulas
import work.nekow.particledrawing.animation.morph.MorphSpace
import work.nekow.particledrawing.animation.morph.MorphWobble
import work.nekow.particledrawing.animation.recipe.GroupExpressionOutputs
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.command.v2.GroupActions.Action
import work.nekow.particledrawing.command.v2.GroupActions.MorphConfig
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.AnimationProgramCodecs
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import java.util.UUID

internal object ProgramCommands {
    data class Step(val action: Action, val morph: MorphConfig? = null)
    data class State(
        val active: Boolean, val cursor: Long, val expression: Boolean,
        val entities: Set<String>, val variables: Set<String>, val elapsed: Long = 0L,
    )

    private val actions = listOf(
        "delay", "pivot", "follow", "anchor", "move", "move_offset", "move_path", "rotate", "recolor",
        "scale_by", "scale_to", "spin", "pulse", "fade_in", "fade_out", "fade_out_keep", "expression",
        "entity", "variable", "stop_continuous", "destroy_after", "retire", "morph",
    )

    fun chain(input: CommandInput): List<(CommandSourceStack) -> Step> = buildList {
        while (input.take("then")) {
            if (size == 4096) input.invalid("up to 4096 actions")
            add(action(input))
        }
    }

    private fun deferred(action: (CommandSourceStack) -> Step) = action

    private fun step(action: Action): (CommandSourceStack) -> Step = { Step(action) }

    private fun action(input: CommandInput): (CommandSourceStack) -> Step {
        val name = input.choice(actions)
        fun ticks(min: Int = 0, label: String = "ticks") = input.integer(label, min, Int.MAX_VALUE / 50)
        fun ease(default: EasingType = EasingType.LINEAR) =
            if (input.take("ease")) ParticleCommandOptionParser.easing(input.value("easing", ParticleCommandOptionParser.easingNames)) else default
        fun axis(): Vec3 = input.vector("axis").also {
            if (it.lengthSqr() == 0.0 || !it.lengthSqr().isFinite()) input.invalid("nonzero axis", it.toString())
        }
        fun ratio(): Float = input.number("ratio").toFloat().also {
            if (!it.isFinite() || it < 0f) input.invalid("nonnegative ratio", it.toString())
        }
        return when (name) {
            "delay" -> step(Action.Delay(ticks()))
            "pivot" -> pivot(input)
            "follow" -> follow(input, true)
            "anchor" -> if (input.choice(listOf("fixed", "entity")) == "fixed") pivot(input) else follow(input, false)
            "move" -> step(Action.Move(input.vector("move"), ticks(), ease()))
            "move_offset" -> {
                val ratio = input.number("ratio").toFloat()
                if (!ratio.isFinite()) input.invalid("finite ratio", ratio.toString())
                step(Action.MoveOffset(ratio, ticks(), ease()))
            }
            "move_path" -> {
                val duration = ticks()
                step(Action.MovePath(GroupActions.parsePath(input.value("points")), duration, ease()))
            }
            "rotate" -> step(Action.Rotate(axis(), input.number("degrees"), ticks(), ease()))
            "recolor" -> step(Action.Recolor(ParticleCommandOptionParser.parseColor(input.value("color", ParticleCommandOptionParser.colorNames)),
                ticks(), ease()))
            "scale_by" -> step(Action.ScaleBy(ratio(), ticks(), ease()))
            "scale_to" -> step(Action.ScaleTo(ratio(), ticks(), ease()))
            "spin" -> step(Action.Spin(axis(), input.number("degrees per tick")))
            "pulse" -> {
                val peak = ratio()
                val half = ticks(1, "half period")
                val cycles = if (input.take("cycles")) input.integer("cycles", -1) else -1
                if (cycles == 0) input.invalid("cycles (-1 or positive)", "0")
                step(Action.Pulse(peak, half, cycles))
            }
            "fade_in" -> step(Action.FadeIn(ticks(), ease(EasingType.EASE_OUT)))
            "fade_out", "fade_out_keep" -> step(Action.FadeOut(ticks(), name == "fade_out_keep", ease(EasingType.EASE_IN)))
            "expression" -> {
                val local = input.take("local")
                val duration = ticks()
                val code = input.formula("assignment block")
                GroupExpressionOutputs.validate(code)
                step(Action.Expression(duration, code, local))
            }
            "entity" -> {
                val handle = GroupActions.requireName(input.value("handle"))
                val selector = input.entity()
                deferred { source -> Step(Action.Entity(handle, selector.resolve(source).uuid)) }
            }
            "variable" -> {
                val verb = input.choice(listOf("set", "live", "ease"))
                val variable = GroupActions.requireName(input.value("variable"))
                if (verb == "set") step(Action.VariableSet(variable, input.number("value")))
                else {
                    val formula = input.formula("value")
                    ScriptRuntime.ExpressionRunner(formula)
                    if (verb == "live") step(Action.VariableLive(variable, formula))
                    else step(Action.VariableEase(variable, formula, ticks(), ease()))
                }
            }
            "stop_continuous" -> step(Action.StopContinuous)
            "destroy_after" -> step(Action.DestroyAfter(ticks()))
            "retire" -> step(Action.Retire(if (input.take("grace")) ticks(label = "grace") else 2))
            else -> {
                val config = morph(input)
                deferred { Step(Action.MorphStart, config) }
            }
        }
    }

    private fun pivot(input: CommandInput): (CommandSourceStack) -> Step {
        val position = input.coordinates()
        return { Step(Action.Pivot(position.getPosition(it))) }
    }

    private fun follow(input: CommandInput, shortcut: Boolean): (CommandSourceStack) -> Step {
        val selector = input.entity()
        val offset = if (input.take("offset")) input.vector("offset") else Vec3.ZERO
        val local = input.take("local")
        return { source ->
            val uuid = selector.resolve(source).uuid
            Step(if (shortcut) Action.Follow(uuid, offset, local) else Action.AnchorEntity(uuid, offset, local))
        }
    }

    private val morphFields = setOf(
        "yaw", "pitch", "size", "control1", "control2", "ticks", "stagger", "loop", "max_speed", "max_accel",
        "converge", "path", "easing", "wobble", "allocation", "dynamic", "keep_velocity", "attributes", "space",
    )

    fun morph(input: CommandInput): MorphConfig {
        val config = MorphConfig()
        input.suggest(listOf("slots", "target"))
        if (input.take("target")) {
            when (val spec = PointSources.parse(input, allowAll = true)) {
                // 公式目标与旧 slots 是同一个东西：保留公式校验、动态槽位与 t 语义。
                is PointSource.Formula -> {
                    if (spec.count == null) config.autoSlots = true else config.slotCount = spec.count
                    config.position = spec.position
                }
                else -> {
                    config.target = spec
                    // 点数要等真正知道成员数或取到快照时才算，草稿先标未知。
                    config.slotCount = 0
                }
            }
        } else {
            input.expect("slots")
            if (input.take("all")) config.autoSlots = true
            else config.slotCount = input.integer("slots", 1, AnimationProgramCodecs.MAX_PROGRAM_MEMBERS)
            config.position = input.formula("position")
        }
        val seen = hashSetOf<String>()
        input.suggest(morphFields)
        while (input.peek() in morphFields) {
            val field = input.choice(morphFields, candidates = morphFields - seen)
            if (!seen.add(field)) input.invalid("distinct morph field", field)
            when (field) {
                "yaw" -> config.yaw = input.formula(field)
                "pitch" -> config.pitch = input.formula(field)
                "size" -> config.size = input.formula(field)
                "control1" -> config.control1 = input.formula(field)
                "control2" -> config.control2 = input.formula(field)
                "ticks" -> config.ticks = input.integer(field, 0)
                "stagger" -> config.stagger = input.integer(field, 0)
                "loop" -> config.loop = input.integer(field, -1)
                "max_speed" -> config.maxSpeed = input.number(field)
                "max_accel" -> config.maxAccel = input.number(field)
                "converge" -> config.converge = input.number(field)
                "path" -> config.path = MorphPath.valueOf(input.choice(listOf("straight", "quadratic", "cubic")).uppercase())
                "easing" -> {
                    val easing = input.value(field, listOf("linear", "in_quad", "out_quad", "in_out_quad", "out_cubic", "in_out_cubic", "out_back"))
                    if (MorphEasing.byName(easing) == null) input.invalid("morph easing", easing)
                    config.easing = easing
                }
                "wobble" -> {
                    config.wobble = MorphWobble.valueOf(input.choice(listOf("none", "bell", "noise", "vortex")).uppercase())
                    if (input.take("strength")) config.wobbleStrength = input.number("strength")
                    if (input.take("peaks")) config.wobblePeaks = input.number("peaks")
                }
                "allocation" -> config.allocation = MorphAllocation.valueOf(input.choice(listOf("nearest", "index", "angle")).uppercase())
                "dynamic" -> config.dynamic = input.boolean()
                "keep_velocity" -> config.keepVelocity = input.boolean()
                "attributes" -> config.attributes = input.boolean()
                "space" -> config.space = MorphSpace.valueOf(input.choice(listOf("world", "local")).uppercase())
            }
            input.suggest(morphFields - seen)
        }
        return config
    }

    fun validateMorph(config: MorphConfig) {
        if (config.target != null) {
            // 点源目标只负责落点；朝向与尺寸要逐槽位公式才能表达，改用 slots。
            requireLocalized(config.yaw == null && config.pitch == null && config.size == null && config.attributes != true) {
                translation("error.morph.target_position_only")
            }
            requireLocalized(!config.dynamic) { translation("error.morph.target_static") }
        } else {
            val slots = if (config.autoSlots) 1 else config.slotCount
            MorphSlotFormulas(slots, config.position, config.yaw, config.pitch, config.size,
                config.control1, config.control2).build(0.0)
        }
        config.options()
    }

    /** 只处理 `slots all`：按成员数落成具体槽位数，其它字段原样保留。 */
    fun resolveAutoSlots(steps: List<Step>, members: Int): List<Step> {
        if (steps.none { it.morph?.autoSlots == true }) return steps
        val slots = members.coerceIn(1, AnimationProgramCodecs.MAX_PROGRAM_MEMBERS)
        return steps.map { step ->
            val morph = step.morph
            if (morph == null || !morph.autoSlots) step
            else step.copy(morph = morph.copy(slotCount = slots, autoSlots = false))
        }
    }

    /**
     * 在命令知道组成员数与锚点的时刻把形变落成具体槽位：`slots all` 取成员数，
     * 点源目标求出点数并缓存采样点。之后的校验、时长账本与下发都用同一份结果；
     * [GroupActions.applyAction] 还会在真正开始时按成员数再兜一次底。
     */
    fun prepareMorphs(steps: List<Step>, members: Int, source: CommandSourceStack, anchor: Vec3): List<Step> {
        val resolved = resolveAutoSlots(steps, members)
        if (resolved.none { it.morph?.target != null }) return resolved
        return resolved.map { step ->
            val morph = step.morph ?: return@map step
            val target = morph.target ?: return@map step
            // 已经求过值的采样点在重复 start 时直接复用，不必重新采样。
            if (morph.targetPoints != null) return@map step
            val prepared = PointSources.prepareTarget(source, target, members)
            requireLocalized(prepared.count > 0) { translation("error.morph.target_empty") }
            step.copy(
                morph = morph.copy(
                    slotCount = prepared.count.coerceIn(1, AnimationProgramCodecs.MAX_PROGRAM_MEMBERS),
                    targetPoints = prepared.points,
                ),
            )
        }
    }

    fun validate(steps: List<Step>, current: State? = null): State {
        val elapsed = current?.elapsed ?: 0L
        var active = current?.active ?: false
        var cursor = current?.cursor ?: 0L
        var expression = current?.expression ?: false
        val entities = current?.entities.orEmpty().toMutableSet()
        val variables = current?.variables.orEmpty().toMutableSet()
        for ((index, step) in steps.withIndex()) {
            val action = step.action
            requireLocalized(!action.requiresActive || active) { translation("error.group.not_started_unnamed") }
            requireLocalized(action.activates || (!active && cursor == 0L)) { translation("error.group.static_declarations") }
            val duration: Long = when (action) {
                is Action.Delay -> {
                    cursor += action.ticks * 50L
                    0L
                }
                is Action.Move -> action.ticks * 50L
                is Action.MoveOffset -> action.ticks * 50L
                is Action.MovePath -> action.ticks * 50L
                is Action.Rotate -> action.ticks * 50L
                is Action.Recolor -> action.ticks * 50L
                is Action.ScaleBy -> action.ticks * 50L
                is Action.ScaleTo -> action.ticks * 50L
                is Action.FadeIn -> action.ticks * 50L
                is Action.FadeOut -> action.ticks * 50L + if (action.keep) 0L else 250L
                is Action.DestroyAfter -> action.ticks * 50L
                is Action.Pulse -> if (action.cycles == -1) action.halfPeriod * 50L else action.halfPeriod * (action.cycles * 100L)
                is Action.Expression -> {
                    expression = true
                    action.ticks * 50L
                }
                is Action.Entity -> {
                    requireLocalized(action.handle !in variables && entities.add(action.handle)) {
                        translation("error.group.handle_taken", action.handle)
                    }
                    0L
                }
                is Action.VariableSet -> {
                    requireLocalized(action.name !in entities && variables.add(action.name)) {
                        translation("error.group.handle_taken", action.name)
                    }
                    0L
                }
                is Action.VariableLive, is Action.VariableEase -> {
                    requireLocalized(cursor == 0L) { translation("error.command.v2.live_cursor") }
                    if (action is Action.VariableEase) action.ticks * 50L else 0L
                }
                is Action.Retire -> {
                    requireLocalized(index == steps.lastIndex) { translation("error.command.v2.retire_last") }
                    action.grace * 50L
                }
                Action.MorphStart -> {
                    requireLocalized(!expression) { translation("error.group.morph_position") }
                    val morph = checkNotNull(step.morph)
                    validateMorph(morph)
                    (morph.ticks.toLong() + morph.stagger.toLong() * (morph.slotCount - 1)) * 50L
                }
                else -> 0L
            }
            requireLocalized(duration in 0..(Int.MAX_VALUE - elapsed - cursor)) { translation("error.command.v2.timeline_limit") }
            if (action.activates && (action !is Action.Delay || action.ticks != 0)) active = true
        }
        return State(active, cursor, expression, entities, variables)
    }

    fun state(entry: CommandParticleGroups.Entry): State {
        val group = entry.group
        val state = entry.programState ?: State(entry.activated, group.programCursorMs.toLong(),
            group.hasProgramExpression, group.programEntities.toSet(), group.programVariables)
        return if (group.hasDeliveredProgram()) state.copy(cursor = 0L, elapsed = group.programElapsedMs) else state
    }

    fun apply(dimension: UUID, entry: CommandParticleGroups.Entry, steps: List<Step>, state: State, replace: Boolean) {
        if (replace) entry.pendingPrograms.clear()
        entry.programmed = true
        entry.activated = state.active
        entry.programState = state
        CommandParticleGroups.runWhenMembersReady(dimension, entry) {
            if (replace) check(entry.group.resetRecordedProgram()) else entry.group.beginCommandAppend()
            apply(entry.group, steps)
        }
    }

    private fun apply(group: ParticleGroup, steps: List<Step>) {
        for (step in steps) GroupActions.applyAction(group, step.action, step.morph)
    }
}
