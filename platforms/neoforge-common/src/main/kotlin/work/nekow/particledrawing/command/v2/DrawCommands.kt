package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.coordinates.Coordinates
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.AnimationProgramCodecs
import work.nekow.particledrawing.core.server.AnimationScheduler
import work.nekow.particledrawing.core.server.ServerParticleEngine
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

internal object DrawCommands {
    private data class Figure(
        val shape: String, val start: Coordinates?, val end: Coordinates?, val numbers: Map<String, Double>,
        val text: String?, val expression: String?, val look: Map<String, String>, val options: Map<String, String>,
        val life: Int, val stagger: Int, val velocity: Vec3?, val force: Vec3?, val forceTicks: Int,
        val edgeGap: Int?, val entrance: ParticleLifeCurve?,
    )

    private data class Particle(val placement: Draw.Placement, val velocity: Vec3?, val force: Vec3?, val forceTicks: Int)

    private val shapes = listOf("dot", "field", "line", "beam", "helix", "circle", "disc", "triangle", "hexagram",
        "rect", "sphere", "cuboid", "text", "sin", "expr")
    private val planes = listOf("xy", "xz", "yz")
    private val planeFigures = setOf("circle", "disc", "triangle", "hexagram", "rect", "sin")
    private val beamLook = setOf("color", "gradient", CommandLook.COLOR_EXPRESSION, "texture", "additive", "glow",
        "alpha_curve", "red_curve", "green_curve", "blue_curve", "size_curve", "light_curve")
    private const val MAX_MEMBERS = AnimationProgramCodecs.MAX_PROGRAM_MEMBERS

    fun parse(input: CommandInput, source: CommandSourceStack): () -> Int {
        val shape = input.choice(shapes)
        val dimension = ParticleUtils.dimensionUUID(source.level)
        val name = if (input.take("as")) input.name(CommandParticleGroups.names(dimension)) else null
        val anchor = if (input.take("group_pivot")) input.coordinates() else null
        val figures = figures(input, figure(input, shape))
        val program = ProgramCommands.chain(input)
        val dryrun = input.end()
        return {
            val pivot = anchor?.getPosition(source) ?: figures.first().start?.getPosition(source) ?: source.position
            if (name != null) CommandParticleGroups.checkCapacity(dimension, name)
            val particles = prepare(figures, source, pivot)
            val maxDelay = particles.maxOf { it.placement.delay }
            val readyDelay = if (maxDelay == 0) 0 else maxDelay + 1
            val steps = ProgramCommands.prepareMorphs(program.map { it(source) }, particles.size, source, pivot)
            val state = ProgramCommands.validate(steps)
            if (steps.isNotEmpty()) {
                requireLocalized(particles.all { particle ->
                    val life = particle.placement.lifetime
                    life == -1 || life > readyDelay - particle.placement.delay
                }) { translation("error.command.v2.draw_life") }
            }
            val manager = ParticleManager.of(source.level)
            val replacing = name?.let { CommandParticleGroups.get(dimension, it)?.group?.id }
            checkCapacity(manager, particles.size, replacing)
            val feedbackName = if (!dryrun) {
                val group = manager.createGroup(pivot)
                val entry = CommandParticleGroups.install(dimension, name, group)
                spawn(group, particles)
                record(entry, readyDelay)
                if (steps.isNotEmpty()) ProgramCommands.apply(dimension, entry, steps, state, false)
                entry.name
            } else name
            feedback(source, feedbackName, "draw", particles.size, dryrun)
        }
    }

    private fun figures(input: CommandInput, initial: Figure): List<Figure> = buildList {
        add(initial)
        while (input.take("also")) {
            if (size == MAX_MEMBERS) input.invalid("figure count")
            add(figure(input, input.choice(shapes)))
        }
    }

    private fun figure(input: CommandInput, shape: String): Figure {
        val segment = shape in setOf("line", "beam")
        input.expect(if (segment) "from" else "at")
        val start = if (shape == "field" && input.take("anchor")) null else input.coordinates()
        val end = if (segment) {
            input.expect("to")
            input.coordinates()
        } else null
        val numbers = linkedMapOf<String, Double>()
        var text: String? = null
        var formula: String? = null
        fun number(field: String, nonnegative: Boolean = true) {
            input.expect(field)
            val value = input.number(field)
            if (nonnegative && value < 0.0) input.invalid("nonnegative $field", value.toString())
            numbers[field] = value
        }
        fun integer(field: String, min: Int = 1, max: Int = MAX_MEMBERS) {
            input.expect(field)
            numbers[field] = input.integer(field, min, max).toDouble()
        }
        when (shape) {
            "line", "field" -> integer("count")
            "helix" -> {
                number("radius")
                number("height")
                number("turns", false)
                integer("count")
            }
            "circle", "sphere" -> {
                number("radius")
                integer("count")
            }
            "disc" -> {
                number("radius")
                integer("perimeter")
                integer("layers", max = MAX_MEMBERS)
            }
            "triangle", "hexagram" -> {
                number("radius")
                integer("segments", max = MAX_MEMBERS)
            }
            "rect", "cuboid" -> {
                if (shape == "cuboid" && input.take("size")) {
                    val size = input.number("size")
                    if (size <= 0.0) input.invalid("positive size", size.toString())
                    numbers["width"] = size
                    numbers["height"] = size
                    numbers["depth"] = size
                } else {
                    number("width")
                    number("height")
                    if (shape == "cuboid") number("depth")
                }
                integer("density", 2, MAX_MEMBERS)
                if (numbers.values.take(if (shape == "cuboid") 3 else 2).all { it == 0.0 }) input.invalid("nonzero size")
            }
            "text" -> {
                input.expect("text")
                text = input.value("text")
                if (input.take("size")) numbers["height"] = input.number("size")
                if (input.take("count")) numbers["count"] = input.integer("count", 1, MAX_MEMBERS).toDouble()
            }
            "sin" -> {
                number("width")
                number("height")
                number("cycles", false)
                integer("count")
            }
            "expr" -> {
                integer("count")
                formula = input.formula("position")
            }
        }
        val options = linkedMapOf<String, String>()
        if (shape in planeFigures && input.take("axis")) options["axis"] = input.choice(planes)
        if (shape == "beam" && input.take("thickness")) options["thickness"] = input.number("thickness").toString()
        if (shape in setOf("line", "beam", "helix")) {
            val taper = if (input.take("taper")) input.flag() else shape == "beam"
            options["taper"] = taper.toString()
        }
        val edgeGap = if (shape == "cuboid" && input.take("edges")) {
            if (listOf("width", "height", "depth").any { numbers.getValue(it) <= 0.0 }) {
                input.invalid("positive width, height and depth for edges")
            }
            if (input.take("edge_gap")) input.integer("edge_gap", 0) else 0
        } else null
        if (edgeGap == null && shape in setOf("rect", "cuboid") && input.take("hollow")) {
            options["hollow"] = input.flag().toString()
        }
        val velocity = if (shape == "field" && input.take("velocity")) input.vector("velocity") else null
        val force = if (shape == "field" && input.take("force")) input.vector("force") else null
        val forceTicks = if (force != null && input.take("force_ticks")) input.integer("force_ticks", -1) else -1
        val look = if (input.take("look")) CommandLook.parse(input, if (shape == "beam") beamLook else CommandLook.fields) else emptyMap()
        if (shape == "beam" && look.keys.any { it !in beamLook }) input.invalid("beam look", look.keys.first { it !in beamLook })
        val life = if (input.take("life")) input.integer("life", -1) else -1
        val stagger = if (input.take("stagger")) input.integer("stagger", 0, Int.MAX_VALUE - 1) else 0
        if (stagger != 0 && shape in setOf("field", "dot")) input.invalid("stagger 0 for $shape", stagger.toString())
        val entrance = if (input.take("enter")) entrance(input) else null
        return Figure(shape, start, end, numbers, text, formula, look, options, life, stagger, velocity, force, forceTicks,
            edgeGap, entrance)
    }

    private fun entrance(input: CommandInput): ParticleLifeCurve {
        val style = input.choice(listOf("fade", "grow", "fade_grow"))
        val ticks = input.integer("enter ticks", 1)
        val easing = if (input.take("ease")) ParticleCommandOptionParser.easing(input.value("easing", ParticleCommandOptionParser.easingNames))
            else EasingType.EASE_OUT_QUAD
        val start = CurveKey.at(0, 0)
        val end = CurveKey.at(ticks, 1, easing)
        return ParticleLifeCurve(buildList {
            if (style != "grow") add(ParticleCurve.alpha(start, end))
            if (style != "fade") add(ParticleCurve.scale(start, end))
        })
    }

    private fun prepare(figures: List<Figure>, source: CommandSourceStack, pivot: Vec3): List<Particle> {
        val manager = ParticleManager.of(source.level)
        val result = ArrayList<Particle>()
        val scratch = ParticleGroup(UUID(0, 0), pivot, manager)
        for (figure in figures) {
            val start = figure.start?.getPosition(source) ?: pivot
            val end = figure.end?.getPosition(source)
            val parsed = CommandLook.options(figure.look, figure.options)
            val options = figure.entrance?.let { parsed.copy(lifeCurve = (parsed.lifeCurve ?: ParticleLifeCurve.EMPTY).plus(it)) }
                ?: parsed
            val positions = when (figure.shape) {
                "dot", "field" -> mapOf("pos" to start)
                "line", "beam" -> mapOf("start" to start, "end" to checkNotNull(end))
                else -> mapOf("center" to start)
            }
            val raw = ArrayList<Draw.Placement>()
            scratch.drawCollector = {
                requireLocalized(result.size + raw.size < MAX_MEMBERS) {
                    translation("error.group.capacity_unnamed", MAX_MEMBERS)
                }
                raw.add(it)
            }
            val edges = figure.edgeGap?.let { FigureDrawing.cuboidEdges(start, figure.numbers) }
            val custom = when (figure.shape) {
                "sin", "expr" -> customPoints(figure, start)
                "field" -> List(figure.numbers.getValue("count").toInt()) { start }
                else -> edges?.map { it.position }
            }
            if (custom != null) {
                Draw.points(manager, start, custom, options.color, options.scale, group = scratch,
                    visual = options.visual, lifeCurve = options.lifeCurve, lifetime = figure.life)
            } else {
                FigureDrawing.render(FigureDrawing.Plan(figure.shape, options, figure.life,
                    positions, figure.numbers, figure.text), manager, scratch)
            }
            requireLocalized(raw.isNotEmpty()) { translation("error.text.points") }
            val color = figure.look[CommandLook.COLOR_EXPRESSION]?.let { vectorExpression(it) }
            for ((i, point) in raw.withIndex()) {
                val edgeDelay = (figure.edgeGap ?: 0).toLong() * (edges?.get(i)?.edge ?: 0)
                val delay = i.toLong() * figure.stagger + edgeDelay
                requireLocalized(delay < Int.MAX_VALUE && point.position.hasFiniteCoordinates() && point.scale.isFinite()) {
                    translation("error.command.v2.draw_range")
                }
                val resolved = color?.invoke(i, raw.size, progress(i, raw.size))?.let {
                    requireLocalized(listOf(it.x, it.y, it.z).all { channel -> channel in 0.0..1.0 }) {
                        translation("error.command.v2.color_range")
                    }
                    Color.of(it.x.toFloat(), it.y.toFloat(), it.z.toFloat(), point.color.a)
                } ?: point.color
                result += Particle(point.copy(color = resolved, delay = delay.toInt()), figure.velocity, figure.force, figure.forceTicks)
            }
        }
        return result
    }

    private fun customPoints(figure: Figure, origin: Vec3): List<Vec3> {
        val count = figure.numbers.getValue("count").toInt()
        val vector = figure.expression?.let(::vectorExpression)
        return List(count) { index ->
            val u = progress(index, count)
            if (vector != null) {
                val point = vector(index, count, u)
                origin.add(point.x, point.y, point.z)
            } else {
                val x = (u - 0.5) * figure.numbers.getValue("width")
                val y = sin(u * 2 * PI * figure.numbers.getValue("cycles")) * figure.numbers.getValue("height")
                when (figure.options["axis"]) {
                    "xz" -> origin.add(x, 0.0, y)
                    "yz" -> origin.add(0.0, x, y)
                    else -> origin.add(x, y, 0.0)
                }
            }
        }
    }

    private fun vectorExpression(formula: String) =
        RecipeExpressions(ParticleRecipe(ParticleSampling.count(1))).drawVector(formula)

    private fun progress(index: Int, count: Int) = if (count <= 1) 0.0 else index.toDouble() / (count - 1)

    private fun Vec3.hasFiniteCoordinates() = x.isFinite() && y.isFinite() && z.isFinite()

    private fun checkCapacity(manager: ParticleManager, count: Int, replacing: UUID? = null) {
        val capacity = ServerParticleEngine.get(manager.dimensionId)?.availableDrawCapacity(replacing)
            ?: ParticleDrawingConfig.SERVER.maxParticlesPerDimension.get()
        requireLocalized(count <= capacity) { translation("error.command.v2.draw_capacity", count, capacity) }
    }

    private fun spawn(group: ParticleGroup, particles: List<Particle>) {
        val manager = group.manager
        val engine = manager.getEngine()
        if (engine.getGroup(group.id) == null) engine.createGroup(group.id, group.pivot)
        engine.reserveDraw(group.id, particles.size)
        fun place(particle: Particle) {
            if (!engine.claimDraw(group.id)) return
            val p = particle.placement
            val builder = manager.create().position(p.position).color(p.color).scale(p.scale).lifetime(p.lifetime)
                .group(group.id).offsetFromPivot(p.position.subtract(group.pivot))
            if (!p.visual.isPristine()) builder.visual(p.visual).glowing(p.visual.glowing).lightLevel(p.visual.lightLevel)
            p.lifeCurve?.let(builder::lifeCurve)
            val handle = checkNotNull(builder.spawn())
            particle.velocity?.let(handle::setVelocity)
            particle.force?.let { handle.applyForce(it, particle.forceTicks) }
        }
        for (particle in particles) {
            val delay = particle.placement.delay
            if (delay == 0) place(particle)
            else AnimationScheduler.scheduleOwned(manager.dimensionId, delay, group.id) { place(particle) }
        }
    }

    private fun record(entry: CommandParticleGroups.Entry, readyDelay: Int) {
        if (readyDelay > 0) entry.membersReadyTick =
            maxOf(entry.membersReadyTick, AnimationScheduler.currentTick() + readyDelay)
    }

    fun advanced(input: CommandInput, source: CommandSourceStack): () -> Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        if (input.take("list")) {
            input.finish()
            return { EmitterCommands.success(source, CommandParticleGroups.names(dimension).joinToString(", "), "list") }
        }
        if (input.take("clear_all")) {
            input.finish()
            return {
                CommandParticleGroups.clearPrepared(dimension)
                EmitterCommands.success(source, "group", "clear_all")
            }
        }
        val name = input.name(CommandParticleGroups.names(dimension))
        return when (val op = input.choice(listOf("create", "reset", "append", "program", "morph"))) {
            "create", "reset" -> {
                input.expect("at")
                val pivot = input.coordinates()
                val dryrun = input.end()
                command {
                    CommandParticleGroups.checkCapacity(dimension, name)
                    requireLocalized(op == "reset" || CommandParticleGroups.get(dimension, name) == null) {
                        translation("error.group.exists", name)
                    }
                    val position = pivot.getPosition(source)
                    if (!dryrun) CommandParticleGroups.install(dimension, name, ParticleManager.of(source.level).createGroup(position))
                    EmitterCommands.success(source, name, if (dryrun) "dryrun" else op)
                }
            }
            "append" -> {
                val figures = figures(input, figure(input, input.choice(shapes)))
                val dryrun = input.end()
                command {
                    val entry = entry(dimension, name)
                    requireLocalized(!entry.programmed) { translation("error.command.v2.group_programmed", name) }
                    val manager = entry.group.manager
                    val particles = prepare(figures, source, entry.group.pivot)
                    val pending = ServerParticleEngine.get(dimension)?.pendingDrawCount(entry.group.id) ?: 0
                    requireLocalized(entry.group.size() + pending + particles.size <= MAX_MEMBERS) {
                        translation("error.group.capacity", name, MAX_MEMBERS)
                    }
                    checkCapacity(manager, particles.size)
                    if (!dryrun) {
                        spawn(entry.group, particles)
                        val delay = particles.maxOf { it.placement.delay }
                        record(entry, if (delay == 0) 0 else delay + 1)
                    }
                    feedback(source, name, op, particles.size, dryrun)
                }
            }
            "program" -> {
                val replace = input.take("replace")
                if (!replace) input.take("append")
                val steps = ProgramCommands.chain(input)
                if (steps.isEmpty()) input.invalid("then")
                val dryrun = input.end()
                command { program(source, dimension, name, steps.map { it(source) }, replace, dryrun) }
            }
            else -> morph(input, source, dimension, name)
        }
    }

    private fun morph(input: CommandInput, source: CommandSourceStack, dimension: UUID, name: String): () -> Int =
        when (val op = input.choice(listOf("configure", "start", "inspect", "reset"))) {
            "configure" -> {
                val morph = ProgramCommands.morph(input)
                val dryrun = input.end()
                command {
                    val entry = entry(dimension, name)
                    ProgramCommands.validateMorph(morph)
                    if (!dryrun) entry.morphDraft = morph
                    EmitterCommands.success(source, name, if (dryrun) "dryrun" else op)
                }
            }
            "start" -> {
                val dryrun = input.end()
                command {
                    val entry = entry(dimension, name)
                    val draft = requireNotNullLocalized(entry.morphDraft) {
                        translation("error.morph.slots_command")
                    }
                    val steps = ProgramCommands.prepareMorphs(
                        listOf(ProgramCommands.Step(GroupActions.Action.MorphStart, draft)),
                        readyMembers(entry), source, entry.group.pivot,
                    )
                    // 记下这次求出的点数与采样点，inspect 能看到实际值；start 之间也不会重复采样。
                    if (!dryrun) entry.morphDraft = steps.single().morph
                    program(source, dimension, name, steps, false, dryrun)
                    1
                }
            }
            else -> {
                input.finish()
                command {
                    val entry = entry(dimension, name)
                    if (op == "reset") entry.morphDraft = null
                    val draft = entry.morphDraft
                    if (draft == null) EmitterCommands.success(source, name, op)
                    else {
                        val slots = draft.target?.let { spec ->
                            val count = draft.preparedTarget?.count ?: 0
                            if (count <= 0) translation("command.v2.morph.target.pending", spec.label())
                            else translation("command.v2.morph.target", spec.label(), count)
                        } ?: if (draft.autoSlots) translation("command.v2.morph.slots.all")
                        else translation("command.v2.morph.slots.count", draft.slotCount)
                        val space = translation("command.v2.morph.space.${draft.space.name.lowercase()}")
                        source.reply({ translation("command.v2.morph", name, slots, draft.ticks, space).component() })
                        1
                    }
                }
            }
        }

    private fun program(
        source: CommandSourceStack, dimension: UUID, name: String, steps: List<ProgramCommands.Step>,
        replace: Boolean, dryrun: Boolean,
    ): Int {
        val entry = entry(dimension, name)
        requireLocalized(entry.group.size() > 0 || CommandParticleGroups.hasPendingMembers(entry)) {
            translation("error.group.no_members", name)
        }
        requireLocalized(!replace || !entry.group.hasDeliveredProgram()) { translation("error.command.v2.program_delivered", name) }
        val steps = ProgramCommands.prepareMorphs(steps, readyMembers(entry), source, entry.group.pivot)
        val state = ProgramCommands.validate(steps, if (replace) null else ProgramCommands.state(entry))
        if (!dryrun) ProgramCommands.apply(dimension, entry, steps, state, replace)
        return feedback(source, name, "program", steps.size, dryrun)
    }

    /** 组成员最终数量：已经出生的加上还在等待出生的。 */
    private fun readyMembers(entry: CommandParticleGroups.Entry): Int {
        val pending = ServerParticleEngine.get(entry.group.manager.dimensionId)?.pendingDrawCount(entry.group.id) ?: 0
        return entry.group.size() + pending
    }

    fun control(source: CommandSourceStack, op: String, input: CommandInput): () -> Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        val pattern = input.namePattern(CommandParticleGroups.names(dimension) + "anon:*")
        input.finish()
        return {
            val names = pattern.select(CommandParticleGroups.names(dimension))
            for (name in names) {
                val entry = entry(dimension, name)
                if (op == "delete" || entry.anonymous) CommandParticleGroups.delete(dimension, name)
                else CommandParticleGroups.install(dimension, name, entry.group.manager.createGroup(entry.initialPivot))
            }
            EmitterCommands.success(source, pattern, op, names.size)
        }
    }

    private fun entry(dimension: UUID, name: String) = requireNotNullLocalized(CommandParticleGroups.get(dimension, name)) {
        translation("error.group.not_found", name)
    }

    private fun command(action: () -> Int) = action

    private fun feedback(source: CommandSourceStack, name: String?, op: String, count: Int, dryrun: Boolean): Int {
        val key = when (op) {
            "program" -> "command.v2.program"
            "append" -> "command.v2.append"
            else -> "command.v2.draw"
        }
        val group = name ?: translation("command.v2.anonymous")
        source.reply({
            translation(key, group, count, if (dryrun) translation("command.v2.dryrun") else "").component()
        })
        return if (dryrun) 1 else count
    }
}
