package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.ParticleEvent
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import work.nekow.particledrawing.api.ParticleImageSampler
import work.nekow.particledrawing.animation.recipe.ParticleProbe
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.api.RecipeEmission
import work.nekow.particledrawing.api.SubEmit
import work.nekow.particledrawing.command.CommandParticleRecipes
import work.nekow.particledrawing.command.CommandParticleRecipes.Draft
import work.nekow.particledrawing.command.CommandParticleRecipes.Sampling
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import work.nekow.particledrawing.util.ParticleUtils

internal data class EmitterDeclaration(private val changes: List<Change>) {
    internal data class Binding(val source: CommandSourceStack?, val name: String) {
        fun child(name: String): ParticleRecipe? {
            if (source == null) return null
            requireLocalized(this.name != name) { translation("error.recipe.event_self") }
            return CommandParticleRecipes.get(ParticleUtils.dimensionUUID(source.level), name).recipe
        }
    }

    internal data class Change(val field: String, val apply: Draft.(Binding) -> Unit)

    val empty get() = changes.isEmpty()
    val changesSampling get() = changes.any { it.field == "sample" }

    fun apply(draft: Draft, binding: Binding) {
        for ((_, change) in changes) draft.change(binding)
    }

    companion object {
        private val outputFields = RecipeExpressions.OUTPUTS - "color"
        private val collisions = mapOf(
            "none" to RecipeCollision.NONE, "solid" to RecipeCollision.SOLID, "liquid" to RecipeCollision.LIQUID,
            "both" to RecipeCollision.SOLID_AND_LIQUID, "entity" to RecipeCollision.ENTITY, "all" to RecipeCollision.ALL,
        )
        private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")
        private val default get() = Draft(RecipeAnchor(CoreVec3.ZERO))

        fun parse(input: CommandInput, names: List<String>): EmitterDeclaration {
            val changes = mutableListOf<Change>()
            val seen = hashSetOf<String>()
            fun add(field: String, repeat: Boolean = false, change: Draft.(Binding) -> Unit) {
                if (!repeat && !seen.add(field)) input.invalid("distinct field", field)
                changes += Change(field, change)
            }
            if (input.take("at")) add("at", change = anchor(input, names))
            if (input.take("sample")) add("sample", change = sampling(input))
            while (input.take("let")) {
                val name = input.identifier()
                val formula = input.formula("let")
                add("let.$name") { recipe = recipe.copy(lets = recipe.lets + (name to formula)) }
            }
            for ((stage, shorthand) in listOf("spawn" to "position", "move" to "velocity")) {
                while (input.take(stage)) {
                    val field = if (input.take("set")) input.output() else shorthand
                    val formula = input.formula(field)
                    add("$stage.$field") {
                        if (stage == "spawn") rawBirth = birth() + (field to formula)
                        else recipe = recipe.copy(motion = recipe.motion + (field to formula))
                    }
                }
            }
            for (stage in listOf("filter", "kill", "orbit")) {
                if (!input.take(stage)) continue
                val formula = input.formula(stage)
                add(stage) {
                    recipe = when (stage) {
                        "filter" -> recipe.copy(where = formula)
                        "kill" -> recipe.copy(kill = formula)
                        else -> recipe.copy(orbit = formula)
                    }
                }
            }
            if (input.take("look")) {
                val look = CommandLook.parse(input)
                if ("gradient" in look) input.invalid("emitter look color", "gradient")
                add("look") {
                    val slots = look.keys.map(CommandLook::slot).toSet()
                    appearance.keys.removeAll { CommandLook.slot(it) in slots }
                    appearance.putAll(look)
                }
            }
            for ((field, range) in mapOf("life" to -1..72000, "rate" to 1..1024, "interval" to 1..72000, "max" to 1..8192)) {
                if (!input.take(field)) continue
                val value = input.integer(field, range.first, range.last)
                add(field) {
                    recipe = when (field) {
                        "life" -> recipe.copy(lifetime = value)
                        "rate" -> recipe.copy(rate = value)
                        "interval" -> recipe.copy(interval = value)
                        else -> recipe.copy(maxAlive = value)
                    }
                }
            }
            if (input.take("window")) {
                val from = input.integer("window", 0, 72000)
                val until = if (input.take("until")) input.integer("until", from + 1, 72000) else -1
                add("window") { recipe = recipe.copy(emitFromTick = from, emitUntilTick = until) }
            }
            if (input.take("spacing")) {
                val spacing = input.number("spacing")
                add("spacing") { recipe = recipe.copy(spacing = spacing) }
            }
            if (input.take("sim_range")) {
                val range = input.number("sim_range")
                val preserve = input.take("preserve") && input.boolean()
                add("sim_range") { recipe = recipe.copy(simulationRange = range, preserveSimulation = preserve) }
            }
            if (input.take("follow")) {
                val follow = input.boolean()
                add("follow") { recipe = recipe.copy(follow = follow) }
            }
            if (input.take("collide")) {
                val mode = input.collision()
                val bounce = if (input.take("bounce")) input.number("bounce") else 0.35
                val friction = if (input.take("friction")) input.number("friction") else 0.55
                val size = if (input.take("half_size")) input.number("half_size") else 0.0
                val steps = if (input.take("steps")) input.integer("steps", 1, 4) else 1
                add("collide") {
                    recipe = recipe.copy(collision = mode, bounce = bounce, friction = friction,
                        collisionHalfSize = size, collisionSteps = steps)
                }
            }
            if (input.take("subemit")) {
                val value = if (input.take("off")) null else {
                    input.expect("count")
                    val count = input.integer("count", 1, 64)
                    val life = if (input.take("life")) input.integer("life", 1, 72000) else 12
                    val speed = if (input.take("speed")) input.number("speed") else SubEmit().speed
                    val scale = if (input.take("scale")) input.number("scale") else SubEmit().scale
                    SubEmit().copy(count = count, lifetime = life, speed = speed, scale = scale)
                }
                add("subemit") { recipe = recipe.copy(subEmit = value) }
            }
            while (input.take("with")) {
                val change = advanced(input, names)
                add(change.field, change.field in setOf("probe", "on"), change.apply)
            }
            return EmitterDeclaration(changes)
        }

        private fun CommandInput.identifier(): String = value("identifier").also {
            if (!identifier.matches(it)) invalid("identifier", it)
        }

        private fun CommandInput.output(): String = value("output", outputFields).also {
            if (!identifier.matches(it) || it == "color") invalid("output", it)
        }

        private fun CommandInput.collision() = collisions.getValue(choice(collisions.keys))

        private fun anchor(input: CommandInput, names: List<String>): Draft.(Binding) -> Unit {
            if (input.take("emitter")) {
                val target = input.name(names)
                return { anchorLink = target }
            }
            input.suggest(listOf("~ ~ ~", "^ ^ ^"))
            val first = input.peek()
            if (first.startsWith("~") || first.startsWith("^") || first.toDoubleOrNull() != null) {
                val position = input.coordinates()
                val offset = if (input.take("offset")) input.vector("offset") else Vec3.ZERO
                return { binding ->
                    anchorLink = null
                    binding.source?.let { anchor = RecipeAnchor(position.getPosition(it).add(offset)) }
                }
            }
            val selector = input.entity()
            val offset = if (input.take("offset")) input.vector("offset") else Vec3.ZERO
            val local = input.take("local")
            return { binding ->
                anchorLink = null
                binding.source?.let {
                    val entity = selector.resolve(it)
                    anchor = RecipeAnchor(entity.position(), entity.uuid, entity.id, offset, local)
                }
            }
        }

        private fun sampling(input: CommandInput): Draft.(Binding) -> Unit =
            when (input.choice(listOf("count", "range", "grid", "image", "mesh", "text"))) {
                "count" -> {
                    val count = input.integer("count", 1, 8192)
                    sampleChange { range = null; this.count = count; sampling = Sampling.PARAMETER }
                }
                "range" -> {
                    val range = Triple(input.number("begin"), input.number("end"), input.number("step"))
                    sampleChange { this.range = range; sampling = Sampling.PARAMETER }
                }
                "grid" -> {
                    input.expect("from")
                    val min = input.vector("from")
                    input.expect("to")
                    val max = input.vector("to")
                    input.expect("step")
                    val step = input.number("step")
                    sampleChange { this.min = min; this.max = max; this.step = step; sampling = Sampling.GRID }
                }
                "image" -> {
                    val source = asset(input)
                    val spacing = if (input.take("pixel_spacing")) input.number("pixel_spacing") else 0.05
                    val alpha = if (input.take("alpha")) input.number("alpha") else 0.0
                    if (spacing <= 0 || alpha !in 0.0..1.0) input.invalid("image spacing and alpha")
                    val stride = if (input.take("stride")) input.integer("stride", 1, 256) else 1
                    val plane = if (input.take("plane")) input.choice(listOf("xy", "xz", "yz")).uppercase() else "XY"
                    val options = ParticleImageSampler.Options(stride, spacing, alpha, ParticleImageSampler.Plane.valueOf(plane))
                    sampleChange { image = source; imageOptions = options; sampling = Sampling.IMAGE }
                }
                "mesh" -> {
                    val source = asset(input)
                    val count = if (input.take("count")) input.integer("count", 1, 8192) else 256
                    val seed = if (input.take("seed")) input.integer("seed") else 0
                    sampleChange { mesh = source; meshCount = count; meshSeed = seed; sampling = Sampling.MESH }
                }
                else -> {
                    val text = input.value("text")
                    if (text.isBlank()) input.invalid("nonempty text")
                    val count = if (input.take("count")) input.integer("count", 1, 8192) else 1024
                    val height = if (input.take("height")) input.number("height") else 2.0
                    if (height !in 0.001..1024.0) input.invalid("height (0.001..1024)", height.toString())
                    sampleChange { this.text = text; textCount = count; textHeight = height; sampling = Sampling.TEXT }
                }
            }

        private fun sampleChange(change: Draft.(Binding) -> Unit) = change

        private fun asset(input: CommandInput): CommandParticleRecipes.SampleAsset {
            val resource = input.choice(listOf("file", "resource")) == "resource"
            val path = input.value("path")
            if (resource) ResourceId.parse(path)
            return CommandParticleRecipes.SampleAsset(path, resource)
        }

        private fun advanced(input: CommandInput, names: List<String>): Change =
            when (val kind = input.choice(listOf("variable", "state", "select", "change", "environment", "probe", "on"))) {
                "variable", "state" -> {
                    val name = input.identifier()
                    val value = input.number(kind)
                    Change("$kind.$name") {
                        recipe = if (kind == "variable") recipe.copy(variables = recipe.variables + (name to value))
                        else recipe.copy(state = recipe.state + (name to value))
                    }
                }
                "select" -> {
                    val formula = input.formula("select")
                    Change("select") { selector = formula }
                }
                "change" -> {
                    val field = input.output()
                    val formula = input.formula(field)
                    Change("change.$field") { changes[field] = formula }
                }
                "environment" -> environment(input)
                "probe" -> probe(input)
                else -> event(input, names)
            }

        private fun environment(input: CommandInput): Change =
            when (val key = input.choice(listOf("entity_radius", "entity_type", "neighbor_radius"))) {
                "entity_radius" -> {
                    val radius = input.number(key)
                    Change("environment.$key") { recipe = recipe.copy(entityQuery = recipe.entityQuery.copy(radius = radius)) }
                }
                "entity_type" -> {
                    val type = input.value(key).takeUnless { it == "any" }?.let(ResourceId::parse)
                    Change("environment.$key") { recipe = recipe.copy(entityQuery = recipe.entityQuery.copy(type = type)) }
                }
                else -> {
                    val radius = input.number(key)
                    Change("environment.$key") { recipe = recipe.copy(neighborRadius = radius) }
                }
            }

        private fun probe(input: CommandInput): Change {
            val action = input.choice(listOf("add", "set", "remove", "clear"))
            if (action == "clear") return Change("probe") { recipe = recipe.copy(probes = emptyList()) }
            val index = if (action in setOf("set", "remove")) input.integer("probe index", 0, ParticleRecipe.MAX_PROBES - 1) else -1
            val probe = if (action == "remove") null else {
                val mode = input.collision()
                if (mode == RecipeCollision.NONE) input.invalid("probe collision", "none")
                val direction = if (input.choice(listOf("velocity", "direction")) == "direction")
                    input.vector("direction").toCore() else null
                val range = input.number("range")
                val origin = if (input.take("origin")) input.vector("origin").toCore() else CoreVec3.ZERO
                val half = if (input.take("halfsize")) input.number("halfsize") else 0.0
                val material = if (input.take("material")) ResourceId.parse(input.value("material")) else null
                ParticleProbe(range, mode, direction, material, origin, half)
            }
            return Change("probe") {
                val result = recipe.probes.toMutableList()
                requireLocalized(index < 0 || index in result.indices) { translation("error.command.v2.expected", "probe index", index) }
                when (action) {
                    "add" -> result.add(checkNotNull(probe))
                    "set" -> result[index] = checkNotNull(probe)
                    else -> result.removeAt(index)
                }
                recipe = recipe.copy(probes = result)
            }
        }

        private fun event(input: CommandInput, names: List<String>): Change {
            val action = input.choice(listOf("birth", "death", "contact", "clear"))
            if (action == "clear") return Change("on") { recipe = recipe.copy(emissions = emptyList()) }
            val event = ParticleEvent.valueOf(action.uppercase())
            val name = input.name(names)
            val material = if (event == ParticleEvent.CONTACT && input.take("material")) ResourceId.parse(input.value("material")) else null
            val decal = if (event == ParticleEvent.CONTACT && input.take("decal")) {
                input.number("decal").also {
                    if (it !in 0.01..RecipeEmission.MAX_DECAL_RADIUS) input.invalid("decal radius", it.toString())
                }
            } else 0.0
            val wrap = decal > 0 && input.take("wrap")
            val once = event != ParticleEvent.CONTACT || !input.take("repeat")
            val remove = event == ParticleEvent.CONTACT && once && input.take("remove_parent")
            return Change("on") { binding ->
                binding.child(name)?.let {
                    recipe = recipe.copy(emissions = recipe.emissions + RecipeEmission(event, it, material, once, remove, decal, wrap))
                }
            }
        }

        fun without(input: CommandInput): EmitterDeclaration {
            val changes = mutableListOf<Change>()
            val seen = hashSetOf<String>()
            while (input.take("without")) {
                val path = input.value("path")
                val section = path.substringBefore('.')
                val field = path.substringAfter('.', "")
                val slot = if (section == "look") CommandLook.slot(field) else field
                val named = setOf("let", "variable", "state", "change")
                val scalar = setOf("at", "sample", "filter", "kill", "orbit", "window", "collide", "subemit",
                    "sim_range", "life", "rate", "interval", "max", "spacing", "follow", "select", "probe", "on")
                val valid = when (section) {
                    "look" -> field.isEmpty() || CommandLook.key(field) in CommandLook.fields
                    "spawn", "move" -> field.isEmpty() || identifier.matches(field)
                    "environment" -> field in setOf("", "entity_radius", "entity_type", "neighbor_radius")
                    in named -> field.isEmpty() || identifier.matches(field)
                    else -> section in scalar && field.isEmpty()
                }
                if (!valid || path.endsWith('.') || !seen.add("$section.$slot")) input.invalid("distinct configuration path", path)
                changes += Change(path) { binding ->
                    requireLocalized(section !in setOf("spawn", "move") || slot.isEmpty() ||
                        slot in outputFields || slot in recipe.state) {
                        translation("error.command.v2.expected", "output field", slot)
                    }
                    clear(section, slot, binding)
                }
            }
            return EmitterDeclaration(changes)
        }

        private fun Draft.clear(section: String, field: String, binding: Binding) {
            val defaults = default.recipe
            fun <T> Map<String, T>.omit(): Map<String, T> = if (field.isEmpty()) emptyMap() else this - field
            when (section) {
                "at" -> {
                    anchorLink = null
                    binding.source?.let { anchor = RecipeAnchor(it.position) }
                }
                "sample" -> { sampling = Sampling.PARAMETER; count = 1; range = null }
                "look" -> appearance.keys.removeAll { field.isEmpty() || CommandLook.slot(it) == field }
                "spawn" -> rawBirth = birth().omit()
                "change" -> {
                    if (field.isEmpty()) changes.clear() else changes.remove(field)
                }
                "select" -> selector = "true"
                else -> recipe = when (section) {
                    "let" -> recipe.copy(lets = recipe.lets.omit())
                    "move" -> recipe.copy(motion = recipe.motion.omit())
                    "variable" -> recipe.copy(variables = recipe.variables.omit())
                    "state" -> recipe.copy(state = recipe.state.omit())
                    "filter" -> recipe.copy(where = defaults.where)
                    "kill" -> recipe.copy(kill = defaults.kill)
                    "orbit" -> recipe.copy(orbit = defaults.orbit)
                    "window" -> recipe.copy(emitFromTick = defaults.emitFromTick, emitUntilTick = defaults.emitUntilTick)
                    "collide" -> recipe.copy(collision = defaults.collision, bounce = defaults.bounce, friction = defaults.friction,
                        collisionHalfSize = defaults.collisionHalfSize, collisionSteps = defaults.collisionSteps)
                    "subemit" -> recipe.copy(subEmit = null)
                    "sim_range" -> recipe.copy(simulationRange = 0.0, preserveSimulation = false)
                    "life" -> recipe.copy(lifetime = defaults.lifetime)
                    "rate" -> recipe.copy(rate = defaults.rate)
                    "interval" -> recipe.copy(interval = defaults.interval)
                    "max" -> recipe.copy(maxAlive = defaults.maxAlive)
                    "spacing" -> recipe.copy(spacing = defaults.spacing)
                    "follow" -> recipe.copy(follow = defaults.follow)
                    "probe" -> recipe.copy(probes = emptyList())
                    "on" -> recipe.copy(emissions = emptyList())
                    "environment" -> recipe.copy(
                        entityQuery = if (field.isEmpty()) defaults.entityQuery else recipe.entityQuery.copy(
                            radius = if (field == "entity_radius") defaults.entityQuery.radius else recipe.entityQuery.radius,
                            type = if (field == "entity_type") null else recipe.entityQuery.type,
                        ),
                        neighborRadius = if (field in setOf("", "neighbor_radius")) defaults.neighborRadius else recipe.neighborRadius,
                    )
                    else -> error("Unknown declaration section $section")
                }
            }
        }

        fun Draft.birth(): Map<String, String> = rawBirth ?: recipe.birth

        fun Draft.prepareRecipe() {
            val options = CommandLook.options(appearance)
            val authored = birth()
            rawBirth = authored
            val writes = linkedMapOf<String, String>()
            appearance["color"]?.let {
                val color = ParticleCommandOptionParser.parseColor(it)
                writes += mapOf("r" to color.r.toString(), "g" to color.g.toString(),
                    "b" to color.b.toString(), "a" to color.a.toString())
            }
            appearance[CommandLook.COLOR_EXPRESSION]?.let { writes["color"] = it }
            if ("scale" in appearance) writes["sc"] = options.scale.toString()
            requireLocalized(writes.keys.none { it in authored }) { translation("error.command.v2.look_conflict") }
            recipe = recipe.copy(birth = authored + writes, visual = options.visual, lifeCurve = options.lifeCurve)
            recipe.validate()
            recipe.copy(birth = changes, where = selector).validate()
        }

        private fun Vec3.toCore() = CoreVec3(x, y, z)
    }
}
