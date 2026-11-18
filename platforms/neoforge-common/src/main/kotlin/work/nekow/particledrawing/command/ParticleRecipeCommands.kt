package work.nekow.particledrawing.command

import work.nekow.particledrawing.i18n.failureComponent
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.LocalizedText
import work.nekow.particledrawing.platform.PlatformCompat
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.animation.recipe.ParticleProbe
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.util.ParticleUtils
import java.util.Locale
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.platform.toCore

/** /pdraw particle emitter：每条命令配置一个职责，公式按属性拆开。 */
internal object ParticleRecipeCommands {
    fun tree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("emitter").requires(PlatformCompat.gamemastersOnly()).executes(::help)
        root.then(Commands.literal("help").executes(::help))
        for (verb in listOf("create", "reset")) root.then(Commands.literal(verb)
            .then(Commands.argument("name", StringArgumentType.string()).executes { ctx -> safe(ctx) {
                val name = word(ctx, "name")
                if (verb == "reset") CommandParticleRecipes.reset(dim(ctx), name, RecipeAnchor(ctx.source.position))
                else CommandParticleRecipes.create(dim(ctx), name, RecipeAnchor(ctx.source.position))
                val message = if (verb == "reset") translation("command.recipe.reset", name)
                    else translation("command.recipe.create", name)
                success(ctx, message)
            } }))
        root.then(Commands.literal("list").executes { ctx -> safe(ctx) { success(ctx, translation("command.recipe.list", CommandParticleRecipes.names(dim(ctx)).joinToString(", "))) } })
        root.then(Commands.literal("copy").then(Commands.argument("from", StringArgumentType.string())
            .then(Commands.argument("to", StringArgumentType.string()).executes { ctx -> safe(ctx) {
                CommandParticleRecipes.copy(dim(ctx), word(ctx, "from"), word(ctx, "to")); success(ctx, translation("command.recipe.copy"))
            } })))

        val name = Commands.argument("name", StringArgumentType.string()).suggests { ctx, b -> SharedSuggestionProvider.suggest(CommandParticleRecipes.names(dim(ctx)).map(StringArgumentType::escapeIfRequired), b) }
        name.then(Commands.literal("inspect").executes { ctx -> safe(ctx) {
            val d = draft(ctx); val r = d.recipe
            success(ctx, translation(
                "command.recipe.inspect", word(ctx, "name"), d.sampling.name.lowercase(), r.rate, r.interval,
                r.lifetime, r.maxAlive, r.follow, d.handle != null, r.birth, r.motion, r.variables, r.lets, r.where, r.kill,
            ))
        } })
        for (verb in listOf("start", "burst", "stop", "remove", "apply", "remove_selected")) name.then(Commands.literal(verb).executes { ctx -> safe(ctx) {
            val d = draft(ctx)
            when (verb) {
                "start", "burst" -> d.start(ctx.source.level, verb == "burst")
                "stop" -> d.handles().forEach { it.stop() }
                "remove" -> d.removeParticles()
                "apply" -> { requireLocalized(d.changes.isNotEmpty()) { translation("error.recipe.change_required") }; d.handles().forEach { it.changeWhere(d.selector, d.changes) } }
                "remove_selected" -> d.handles().forEach { it.removeWhere(d.selector) }
            }
            val emitter = word(ctx, "name")
            val message = when (verb) {
                "start", "burst" -> {
                    val recipe = d.recipe
                    translation("command.recipe.started", emitter, verb, recipe.seeds.size, recipe.maxAlive)
                }
                else -> translation("command.recipe.action", emitter, verb)
            }
            if ((verb == "start" || verb == "burst") && d.recipe.seeds.size > d.recipe.maxAlive) {
                ctx.source.sendSuccess({ translation("command.recipe.raise_limit").component() }, false)
            }
            success(ctx, message)
        } })
        name.then(Commands.literal("delete").executes { ctx -> safe(ctx) { CommandParticleRecipes.remove(dim(ctx), word(ctx, "name")); success(ctx, translation("command.recipe.delete")) } })
        name.then(anchorTree())
        name.then(sampleTree())
        name.then(imageTree())
        name.then(meshTree())
        name.then(eventTree())
        name.then(environmentTree())
        name.then(probeTree())
        for (key in listOf("rate", "interval", "lifetime", "max_alive")) name.then(Commands.literal(key)
            .then(Commands.argument("value", IntegerArgumentType.integer()).executes { ctx -> configure(ctx) { d ->
                val v = IntegerArgumentType.getInteger(ctx, "value")
                d.update(when (key) { "rate" -> d.recipe.copy(rate = v); "interval" -> d.recipe.copy(interval = v); "lifetime" -> d.recipe.copy(lifetime = v); else -> d.recipe.copy(maxAlive = v) })
            } }))
        name.then(Commands.literal("spacing").then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.0, ParticleRecipe.MAX_SPACING))
            .executes { ctx -> configure(ctx) { d ->
                d.update(d.recipe.copy(spacing = DoubleArgumentType.getDouble(ctx, "distance")))
            } }))
        name.then(Commands.literal("simulation_range")
            .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.0, ParticleRecipe.MAX_SIMULATION_RANGE))
                .executes { ctx -> configure(ctx) { d ->
                    d.update(d.recipe.copy(simulationRange = DoubleArgumentType.getDouble(ctx, "distance")))
                } }))
        name.then(Commands.literal("follow").then(Commands.argument("enabled", BoolArgumentType.bool()).executes { ctx -> configure(ctx) { d -> d.update(d.recipe.copy(follow = BoolArgumentType.getBool(ctx, "enabled"))) } }))

        for (kind in listOf("variable", "state")) name.then(Commands.literal(kind)
            .then(Commands.argument("field", StringArgumentType.word())
                .then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes { ctx -> configure(ctx) { d ->
                    val field = word(ctx, "field"); val value = DoubleArgumentType.getDouble(ctx, "value")
                    if (kind == "variable") d.variable(field, value) else d.update(d.recipe.copy(state = d.recipe.state + (field to value)))
                } })))
        for (stage in listOf("let", "birth", "motion", "change")) name.then(Commands.literal(stage)
            .then(Commands.argument("field", StringArgumentType.word()).suggests { ctx, b -> SharedSuggestionProvider.suggest(RecipeExpressions.OUTPUTS + draft(ctx).recipe.state.keys, b) }
                .then(Commands.argument("formula", StringArgumentType.greedyString()).executes { ctx -> configure(ctx) { d ->
                    val field = word(ctx, "field"); val formula = word(ctx, "formula")
                    when (stage) {
                        "let" -> d.update(d.recipe.copy(lets = d.recipe.lets + (field to formula)))
                        "birth" -> d.update(d.recipe.copy(birth = d.recipe.birth + (field to formula)))
                        "motion" -> d.update(d.recipe.copy(motion = d.recipe.motion + (field to formula)))
                        else -> { val next = d.changes + (field to formula); d.recipe.copy(birth = next, where = d.selector).validate(); d.changes[field] = formula }
                    }
                } })))
        // 锚点偏移：叠加在 anchor 的原点上，按发射器运行毫秒 t 求值。已出生的粒子不跟着走，
        // 发射器跑过的轨迹就留下一串粒子。
        name.then(Commands.literal("orbit")
            .then(Commands.literal("unset").executes { ctx -> configure(ctx) { d -> d.update(d.recipe.copy(orbit = "")) } })
            .then(Commands.argument("formula", StringArgumentType.greedyString()).executes { ctx -> configure(ctx) { d ->
                d.update(d.recipe.copy(orbit = word(ctx, "formula")))
            } }))
        name.then(Commands.literal("collide")
            .then(Commands.argument("mode", StringArgumentType.word())
                .suggests { _, b -> SharedSuggestionProvider.suggest(collisionModes.keys, b) }
                .executes { ctx -> configure(ctx) { d ->
                    d.update(d.recipe.copy(collision = collisionMode(ctx)))
                } }))
        name.then(Commands.literal("collision_size")
            .then(Commands.argument("halfSize", DoubleArgumentType.doubleArg(0.0, ParticleRecipe.MAX_COLLISION_HALF_SIZE))
                .executes { ctx -> configure(ctx) { it.update(it.recipe.copy(collisionHalfSize = DoubleArgumentType.getDouble(ctx, "halfSize"))) } }))
        name.then(Commands.literal("collision_steps")
            .then(Commands.argument("steps", IntegerArgumentType.integer(1, ParticleRecipe.MAX_COLLISION_STEPS))
                .executes { ctx -> configure(ctx) { it.update(it.recipe.copy(collisionSteps = IntegerArgumentType.getInteger(ctx, "steps"))) } }))
        // 子发射：粒子正常死亡时在原地炸出一批更小更短命的粒子，只做一层防指数爆炸。
        name.then(Commands.literal("subemit")
            .then(Commands.literal("off").executes { ctx -> configure(ctx) { d ->
                d.update(d.recipe.copy(subEmit = null))
            } })
            .then(Commands.literal("count").then(Commands.argument("value", IntegerArgumentType.integer(1, SubEmit.MAX_COUNT))
                .executes { ctx -> configure(ctx) { d ->
                    d.update(d.recipe.copy(subEmit = (d.recipe.subEmit ?: SubEmit()).copy(count = IntegerArgumentType.getInteger(ctx, "value"))))
                } }))
            .then(Commands.literal("lifetime").then(Commands.argument("value", IntegerArgumentType.integer(1, 200))
                .executes { ctx -> configure(ctx) { d ->
                    d.update(d.recipe.copy(subEmit = (d.recipe.subEmit ?: SubEmit()).copy(lifetime = IntegerArgumentType.getInteger(ctx, "value"))))
                } }))
            .then(Commands.literal("speed").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, SubEmit.MAX_SPEED))
                .executes { ctx -> configure(ctx) { d ->
                    d.update(d.recipe.copy(subEmit = (d.recipe.subEmit ?: SubEmit()).copy(speed = DoubleArgumentType.getDouble(ctx, "value"))))
                } }))
            .then(Commands.literal("scale").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.05, 4.0))
                .executes { ctx -> configure(ctx) { d ->
                    d.update(d.recipe.copy(subEmit = (d.recipe.subEmit ?: SubEmit()).copy(scale = DoubleArgumentType.getDouble(ctx, "value"))))
                } })))
        for (field in listOf("bounce", "friction")) name.then(Commands.literal(field)
            .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 1.0)).executes { ctx -> configure(ctx) { d ->
                val value = DoubleArgumentType.getDouble(ctx, "value")
                d.update(if (field == "bounce") d.recipe.copy(bounce = value) else d.recipe.copy(friction = value))
            } }))

        for (kind in listOf("where", "kill", "select")) name.then(Commands.literal(kind)
            .then(Commands.argument("formula", StringArgumentType.greedyString()).executes { ctx -> configure(ctx) { d ->
                val f = word(ctx, "formula")
                when (kind) { "where" -> d.update(d.recipe.copy(where = f)); "kill" -> d.update(d.recipe.copy(kill = f)); else -> { d.recipe.copy(where = f).validate(); d.selector = f } }
            } }))
        name.then(Commands.literal("unset").then(Commands.argument("section", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("variable", "state", "let", "birth", "motion", "change"), b) }
            .then(Commands.argument("field", StringArgumentType.word()).executes { ctx -> configure(ctx) { d ->
                val field = word(ctx, "field")
                when (word(ctx, "section")) {
                    "variable" -> d.update(d.recipe.copy(variables = d.recipe.variables - field))
                    "state" -> d.update(d.recipe.copy(state = d.recipe.state - field))
                    "let" -> d.update(d.recipe.copy(lets = d.recipe.lets - field))
                    "birth" -> d.update(d.recipe.copy(birth = d.recipe.birth - field))
                    "motion" -> d.update(d.recipe.copy(motion = d.recipe.motion - field))
                    "change" -> d.changes.remove(field)
                    else -> throw LocalizedArgumentException(translation("error.recipe.section"))
                }
            } })))

        name.then(Commands.literal("texture").then(Commands.argument("value", ParticleCommandArguments.textType()).executes { ctx -> configure(ctx) { appearance(it, "texture", ParticleCommandArguments.text(word(ctx, "value"))) } }))
        name.then(Commands.literal("appearance").then(Commands.argument("key", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(ParticleCommandOptionParser.appearanceKeys, b) }
            .then(Commands.argument("value", StringArgumentType.greedyString()).executes { ctx -> configure(ctx) { appearance(it, word(ctx, "key"), word(ctx, "value")) } })))
        root.then(name)
        return root
    }

    private fun environmentTree(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("environment")
            .then(Commands.literal("entity_radius")
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0, 32.0)).executes { ctx -> configure(ctx) { draft ->
                    val query = draft.recipe.entityQuery.copy(radius = DoubleArgumentType.getDouble(ctx, "radius"))
                    draft.update(draft.recipe.copy(entityQuery = query))
                } }))
            .then(Commands.literal("entity_type")
                .then(Commands.literal("any").executes { ctx -> configure(ctx) { draft ->
                    draft.update(draft.recipe.copy(entityQuery = draft.recipe.entityQuery.copy(type = null)))
                } })
                .then(Commands.argument("type", ParticleCommandArguments.textType()).executes { ctx -> configure(ctx) { draft ->
                    val type = ResourceId.parse(ParticleCommandArguments.text(word(ctx, "type")))
                    draft.update(draft.recipe.copy(entityQuery = draft.recipe.entityQuery.copy(type = type)))
                } }))
            .then(Commands.literal("neighbor_radius")
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.05, 16.0)).executes { ctx -> configure(ctx) { draft ->
                    draft.update(draft.recipe.copy(neighborRadius = DoubleArgumentType.getDouble(ctx, "radius")))
                } }))
    }

    private val collisionModes = mapOf(
        "none" to RecipeCollision.NONE,
        "solid" to RecipeCollision.SOLID,
        "liquid" to RecipeCollision.LIQUID,
        "both" to RecipeCollision.SOLID_AND_LIQUID,
        "entity" to RecipeCollision.ENTITY,
        "all" to RecipeCollision.ALL,
    )

    private fun collisionMode(ctx: CommandContext<CommandSourceStack>): RecipeCollision =
        collisionModes[word(ctx, "mode")] ?: throw LocalizedArgumentException(translation("error.recipe.collision_mode"))

    private fun probeTree(): LiteralArgumentBuilder<CommandSourceStack> {
        fun range(fixed: Boolean): RequiredArgumentBuilder<CommandSourceStack, Double> {
            fun apply(ctx: CommandContext<CommandSourceStack>, hasMaterial: Boolean) = configure(ctx) { draft ->
                val direction = if (fixed) ParticleCommandArguments.vector(word(ctx, "direction")).toCore() else null
                val material = if (hasMaterial) ResourceId.parse(ParticleCommandArguments.text(word(ctx, "probeMaterial"))) else null
                val probe = ParticleProbe(DoubleArgumentType.getDouble(ctx, "range"), collisionMode(ctx), direction, material)
                draft.update(draft.recipe.copy(probe = probe))
            }
            return Commands.argument("range", DoubleArgumentType.doubleArg(ParticleProbe.MIN_RANGE, ParticleProbe.MAX_RANGE))
                .executes { ctx -> apply(ctx, hasMaterial = false) }
                .then(Commands.literal("material")
                    .then(Commands.argument("probeMaterial", ParticleCommandArguments.textType()).executes { ctx ->
                        apply(ctx, hasMaterial = true)
                    }))
        }
        return Commands.literal("probe")
            .then(Commands.literal("off").executes { ctx -> configure(ctx) { it.update(it.recipe.copy(probe = null)) } })
            .then(Commands.argument("mode", StringArgumentType.word())
                .suggests { _, b -> SharedSuggestionProvider.suggest(collisionModes.keys.filter { it != "none" }, b) }
                .then(Commands.literal("velocity").then(range(fixed = false)))
                .then(Commands.literal("direction").then(Commands.argument("direction", StringArgumentType.string())
                    .then(range(fixed = true)))))
    }

    private fun eventTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("on")
        root.then(Commands.literal("clear").executes { ctx ->
            configure(ctx) { it.update(it.recipe.copy(emissions = emptyList())) }
        })
        for (event in ParticleEvent.entries) {
            val target = Commands.argument("child", StringArgumentType.string())
                .suggests { ctx, builder -> SharedSuggestionProvider.suggest(CommandParticleRecipes.names(dim(ctx))
                    .filter { it != word(ctx, "name") }.map(StringArgumentType::escapeIfRequired), builder) }
                .executes { ctx -> addEvent(ctx, event) }
            if (event == ParticleEvent.CONTACT) {
                for (option in listOf("remove_parent", "repeat")) target.then(Commands.literal(option).executes { ctx ->
                    addEvent(ctx, event, once = option != "repeat", removeParent = option == "remove_parent")
                })
                target.then(decalTree(event))
                target.then(Commands.literal("material")
                    .then(Commands.argument("material", StringArgumentType.string())
                        .executes { ctx -> addEvent(ctx, event, material = eventMaterial(ctx)) }
                        .then(Commands.literal("remove_parent").executes { ctx ->
                            addEvent(ctx, event, material = eventMaterial(ctx), removeParent = true)
                        })
                        .then(decalTree(event, ::eventMaterial))
                        .then(Commands.literal("repeat").executes { ctx ->
                            addEvent(ctx, event, material = eventMaterial(ctx), once = false)
                        })))
            }
            root.then(Commands.literal(event.name.lowercase()).then(target))
        }
        return root
    }

    private fun decalTree(
        event: ParticleEvent, material: (CommandContext<CommandSourceStack>) -> ResourceId? = { null },
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("decal").then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.01, RecipeEmission.MAX_DECAL_RADIUS))
            .executes { ctx -> addEvent(ctx, event, material(ctx), decalRadius = DoubleArgumentType.getDouble(ctx, "radius")) })

    private fun eventMaterial(ctx: CommandContext<CommandSourceStack>) =
        ResourceId.parse(word(ctx, "material"))

    private fun addEvent(
        ctx: CommandContext<CommandSourceStack>, event: ParticleEvent, material: ResourceId? = null,
        once: Boolean = true, removeParent: Boolean = false, decalRadius: Double = 0.0,
    ) = configure(ctx) { draft ->
        val target = CommandParticleRecipes.get(dim(ctx), word(ctx, "child"))
        requireLocalized(target !== draft) { translation("error.recipe.event_self") }
        val snapshot = target.recipe.copy(seeds = target.samples(ctx.source.level), visual = target.recipe.visual.copy())
        val rule = RecipeEmission(event, snapshot, material, once, removeParent, decalRadius)
        draft.update(draft.recipe.copy(emissions = draft.recipe.emissions + rule))
    }

    private fun anchorTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("anchor")
        root.then(Commands.literal("fixed").then(Commands.argument("pos", Vec3Argument.vec3()).executes { ctx -> configure(ctx) {
            CommandParticleRecipes.anchor(dim(ctx), word(ctx, "name"), RecipeAnchor(Vec3Argument.getVec3(ctx, "pos")))
        } }))
        root.then(Commands.literal("entity").then(Commands.argument("entity", EntityArgument.entity()).executes(::entityAnchor)
            .then(Commands.argument("offset_x", DoubleArgumentType.doubleArg())
                .then(Commands.argument("offset_y", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("offset_z", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("local", BoolArgumentType.bool()).executes(::entityAnchor)))))))
        root.then(Commands.literal("emitter").then(Commands.argument("target", StringArgumentType.string()).executes { ctx -> configure(ctx) {
            CommandParticleRecipes.linkAnchor(dim(ctx), word(ctx, "name"), word(ctx, "target"))
        } }))
        return root
    }

    private fun entityAnchor(ctx: CommandContext<CommandSourceStack>) = configure(ctx) {
        val e = EntityArgument.getEntity(ctx, "entity")
        val offset = try { Vec3(DoubleArgumentType.getDouble(ctx, "offset_x"), DoubleArgumentType.getDouble(ctx, "offset_y"), DoubleArgumentType.getDouble(ctx, "offset_z")) } catch (_: IllegalArgumentException) { Vec3.ZERO }
        val local = try { BoolArgumentType.getBool(ctx, "local") } catch (_: IllegalArgumentException) { false }
        val a = RecipeAnchor(e.position(), e.uuid, e.id, offset, local)
        CommandParticleRecipes.anchor(dim(ctx), word(ctx, "name"), a)
    }

    private fun sampleTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("sample")
        root.then(Commands.literal("count").then(Commands.argument("count", IntegerArgumentType.integer(1, ParticleRecipe.MAX_SEEDS)).executes { ctx -> sampleConfig(ctx) { d ->
            d.sampling = CommandParticleRecipes.Sampling.PARAMETER; d.count = IntegerArgumentType.getInteger(ctx, "count"); d.range = null
        } }))
        root.then(Commands.literal("range").then(Commands.argument("begin", DoubleArgumentType.doubleArg())
            .then(Commands.argument("end", DoubleArgumentType.doubleArg()).then(Commands.argument("step", DoubleArgumentType.doubleArg()).executes { ctx -> sampleConfig(ctx) { d ->
                val begin = DoubleArgumentType.getDouble(ctx, "begin"); val end = DoubleArgumentType.getDouble(ctx, "end"); val step = DoubleArgumentType.getDouble(ctx, "step")
                ParticleSampling.range(begin, end, step)
                d.sampling = CommandParticleRecipes.Sampling.PARAMETER; d.range = Triple(begin, end, step)
            } }))))
        root.then(Commands.literal("grid").executes { ctx -> sampleConfig(ctx) { it.sampling = CommandParticleRecipes.Sampling.GRID } })
        root.then(Commands.literal("bounds").then(Commands.argument("bounds", ParticleCommandArguments.textType()).executes { ctx -> sampleConfig(ctx) { d ->
                val (min, max) = ParticleCommandArguments.bounds(word(ctx, "bounds")); ParticleSampling.grid(min, max, d.step)
                d.min = min; d.max = max
            } }))
        root.then(Commands.literal("step").then(Commands.argument("step", DoubleArgumentType.doubleArg(0.000001)).executes { ctx -> sampleConfig(ctx) { d ->
            val step = DoubleArgumentType.getDouble(ctx, "step"); ParticleSampling.grid(d.min, d.max, step); d.step = step
        } }))
        return root
    }

    private fun imageTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = samplingAssetTree("image", CommandParticleRecipes.Sampling.IMAGE) { draft, asset -> draft.image = asset }
        root.then(Commands.literal("stride").then(Commands.argument("stride", IntegerArgumentType.integer(1, 256)).executes { ctx -> sampleConfig(ctx) { d -> d.imageOptions = d.imageOptions.copy(stride = IntegerArgumentType.getInteger(ctx, "stride")) } }))
        for (key in listOf("spacing", "alpha")) root.then(Commands.literal(key).then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes { ctx -> sampleConfig(ctx) { d ->
            val v = DoubleArgumentType.getDouble(ctx, "value")
            requireLocalized(v.isFinite() && (if (key == "spacing") v > 0 else v in 0.0..1.0)) { translation("error.recipe.sample_options") }
            d.imageOptions = if (key == "spacing") d.imageOptions.copy(spacing = v) else d.imageOptions.copy(alpha = v)
        } }))
        root.then(Commands.literal("plane").then(Commands.argument("plane", StringArgumentType.word()).suggests { _, b -> SharedSuggestionProvider.suggest(listOf("xy", "xz", "yz"), b) }
            .executes { ctx -> sampleConfig(ctx) { d -> d.imageOptions = d.imageOptions.copy(plane = ParticleImageSampler.Plane.valueOf(word(ctx, "plane").uppercase(Locale.ROOT))) } }))
        return root
    }

    private fun samplingAssetTree(
        name: String, sampling: CommandParticleRecipes.Sampling,
        assign: (CommandParticleRecipes.Draft, CommandParticleRecipes.SampleAsset) -> Unit,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal(name)
        for (kind in listOf("file", "resource")) {
            root.then(Commands.literal(kind).then(Commands.argument("path", ParticleCommandArguments.textType())
                .executes { ctx -> sampleConfig(ctx) { draft ->
                    val path = ParticleCommandArguments.text(word(ctx, "path"))
                    assign(draft, CommandParticleRecipes.SampleAsset(path, kind == "resource"))
                    draft.sampling = sampling
                } }))
        }
        return root
    }

    private fun meshTree(): LiteralArgumentBuilder<CommandSourceStack> =
        samplingAssetTree("mesh", CommandParticleRecipes.Sampling.MESH) { draft, asset -> draft.mesh = asset }
            .then(Commands.literal("count")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, ParticleRecipe.MAX_SEEDS))
                    .executes { ctx -> sampleConfig(ctx) { it.meshCount = IntegerArgumentType.getInteger(ctx, "count") } }))
            .then(Commands.literal("seed")
                .then(Commands.argument("seed", IntegerArgumentType.integer())
                    .executes { ctx -> sampleConfig(ctx) { it.meshSeed = IntegerArgumentType.getInteger(ctx, "seed") } }))

    private fun appearance(d: CommandParticleRecipes.Draft, key: String, value: String) {
        requireLocalized(key in ParticleCommandOptionParser.appearanceKeys) { translation("error.recipe.appearance_key", key) }
        val text = ParticleCommandArguments.text(value)
        val values = LinkedHashMap(d.appearance).apply { put(key, text) }
        val parsed = ParticleCommandOptionParser.parse(values.entries.joinToString(" ") {
            "${it.key}=\"${it.value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        })
        requireLocalized(parsed.provided == values.keys) { translation("error.recipe.appearance_value") }
        var birth = d.recipe.birth
        if (key == "color") {
            val c = ParticleCommandOptionParser.parseColor(text)
            birth = birth + mapOf("r" to c.r.toString(), "g" to c.g.toString(), "b" to c.b.toString(), "a" to c.a.toString())
        }
        if (key == "scale") birth = birth + ("sc" to parsed.scale.toString())
        d.update(d.recipe.copy(visual = parsed.visual, lifeCurve = parsed.lifeCurve, birth = birth))
        d.appearance.clear(); d.appearance.putAll(values)
    }

    private fun sampleConfig(ctx: CommandContext<CommandSourceStack>, action: (CommandParticleRecipes.Draft) -> Unit) = configure(ctx) { d ->
        requireLocalized(d.handle == null) { translation("error.recipe.running_sampling") }
        action(d)
    }

    private fun configure(ctx: CommandContext<CommandSourceStack>, action: (CommandParticleRecipes.Draft) -> Unit) = safe(ctx) {
        action(draft(ctx)); success(ctx, translation("command.recipe.update", word(ctx, "name")))
    }
    private fun draft(ctx: CommandContext<CommandSourceStack>) = CommandParticleRecipes.get(dim(ctx), word(ctx, "name"))
    private fun dim(ctx: CommandContext<CommandSourceStack>) = ParticleUtils.dimensionUUID(ctx.source.level)
    private fun word(ctx: CommandContext<CommandSourceStack>, name: String) = StringArgumentType.getString(ctx, name)
    private fun success(ctx: CommandContext<CommandSourceStack>, message: LocalizedText): Int {
        ctx.source.sendSuccess({ message.component() }, false)
        return 1
    }
    private inline fun safe(ctx: CommandContext<CommandSourceStack>, action: () -> Int): Int = try { action() } catch (e: Exception) {
        ctx.source.sendFailure(e.failureComponent("error.recipe.parameters"))
        0
    }
    private fun help(ctx: CommandContext<CommandSourceStack>) = success(ctx,
        translation("command.recipe.usage"))

}
