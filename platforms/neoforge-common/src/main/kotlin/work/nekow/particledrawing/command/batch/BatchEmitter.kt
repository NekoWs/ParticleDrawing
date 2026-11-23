package work.nekow.particledrawing.command.batch

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import work.nekow.particledrawing.api.ParticleImageSampler
import work.nekow.particledrawing.api.ParticleMeshSampler
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.api.ParticleSeed
import work.nekow.particledrawing.api.ParticleTextSampler
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.api.SubEmit
import work.nekow.particledrawing.command.CommandParticleRecipes
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.command.ParticleCommandOptions
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import java.util.UUID

/**
 * `kind=emitter` 的批量配置。
 *
 * 先把键解析成独立的值对象并完成全部校验，通过后才写进 [CommandParticleRecipes] 的草稿；
 * 中途任何一项不合法都不会动到现有配置。
 */
internal object BatchEmitter {

    data class Plan(
        var keep: Boolean = false,
        var mode: String = "start",
        var sampling: CommandParticleRecipes.Sampling = CommandParticleRecipes.Sampling.PARAMETER,
        var count: Int = 1,
        var range: Triple<Double, Double, Double>? = null,
        var min: Vec3 = Vec3(-1.0, -1.0, -1.0),
        var max: Vec3 = Vec3(1.0, 1.0, 1.0),
        var step: Double = 0.25,
        var image: CommandParticleRecipes.SampleAsset? = null,
        var mesh: CommandParticleRecipes.SampleAsset? = null,
        var meshCount: Int = 256,
        var meshSeed: Int = 0,
        var imageOptions: ParticleImageSampler.Options = ParticleImageSampler.Options(),
        /** fixed / entity / emitter；`keep` 表示沿用原锚点。 */
        var anchorKind: String = "fixed",
        var anchorPos: Vec3? = null,
        var anchorEntity: String? = null,
        var anchorOffset: Vec3 = Vec3.ZERO,
        var anchorLocal: Boolean = false,
        var anchorLink: String? = null,
        var recipe: ParticleRecipe = ParticleRecipe(ParticleSampling.count(1)),
        val appearance: MutableMap<String, String> = linkedMapOf(),
        var appearanceDirty: Boolean = false,
        var imageOptionsTouched: Boolean = false,
        var text: String? = null,
        var textCount: Int = 1024,
        var textHeight: Double = 2.0,
        var meshOptionsTouched: Boolean = false,
    ) {
        fun sample(level: ServerLevel): List<ParticleSeed> = when (sampling) {
            CommandParticleRecipes.Sampling.PARAMETER ->
                range?.let { ParticleSampling.range(it.first, it.second, it.third) } ?: ParticleSampling.count(count)
            CommandParticleRecipes.Sampling.GRID -> ParticleSampling.grid(min.toCore(), max.toCore(), step)
            CommandParticleRecipes.Sampling.IMAGE -> {
                val source = requireNotNullLocalized(image) { translation("error.recipe.image_source") }
                source.open(level, "particleImages", "error.image.missing").use {
                    ParticleImageSampler.sample(it, imageOptions)
                }
            }
            CommandParticleRecipes.Sampling.MESH -> {
                val source = requireNotNullLocalized(mesh) { translation("error.mesh.source") }
                source.open(level, "particleMeshes", "error.mesh.missing").use {
                    ParticleMeshSampler.surface(ParticleMeshSampler.objProvider(it), meshCount, meshSeed)
                }
            }
            CommandParticleRecipes.Sampling.TEXT -> ParticleTextSampler.sample(
                requireNotNullLocalized(text) { translation("error.recipe.text_source") }.replace('|', '\n'),
                textCount, textHeight,
            ).map(::ParticleSeed)
        }
    }

    /** 解析；`existing` 只在 `keep=true` 时作为基底。 */
    fun parse(tokens: List<BatchToken>, existing: CommandParticleRecipes.Draft?, source: CommandSourceStack): Plan {
        val keep = tokens.firstOrNull { it.key == "keep" }?.let { BatchOptions.bool(it.value, it.key) } ?: false
        val plan = Plan(keep = keep, mode = tokens.firstOrNull { it.key == "mode" }?.let { BatchOptions.value(it, listOf("start", "burst", "config", "start_instanced", "burst_instanced", "burst_gpu", "start_synced", "burst_synced")) } ?: "start")
        if (keep && existing != null) {
            plan.sampling = existing.sampling
            plan.count = existing.count
            plan.range = existing.range
            plan.min = existing.min
            plan.max = existing.max
            plan.step = existing.step
            plan.image = existing.image
            plan.mesh = existing.mesh
            plan.meshCount = existing.meshCount
            plan.meshSeed = existing.meshSeed
            plan.imageOptions = existing.imageOptions
            plan.text = existing.text
            plan.textCount = existing.textCount
            plan.textHeight = existing.textHeight
            plan.recipe = existing.recipe
            plan.appearance.putAll(existing.appearance)
            plan.anchorKind = "keep"
        }
        val seen = HashMap<String, Int>()
        for (token in tokens) {
            val spec = BatchKeys.require(token.key, BatchKind.EMITTER)
            if (!spec.repeatable) {
                val count = seen.merge(token.key, 1, Int::plus) ?: 1
                requireLocalized(count <= 1) { translation("error.batch.duplicate_key", token.key) }
            }
            when (token.key) {
                "kind", "keep", "mode", "dryrun" -> Unit
                "anchor" -> plan.anchorKind = BatchOptions.value(token, listOf("fixed", "entity", "emitter"))
                "anchor.pos" -> plan.anchorPos = BatchOptions.position(token.value, token.key).resolve(source)
                "anchor.entity" -> plan.anchorEntity = BatchOptions.text(token.value)
                "anchor.offset" -> plan.anchorOffset = BatchOptions.offset(token.value, token.key)
                "anchor.local" -> plan.anchorLocal = BatchOptions.bool(token.value, token.key)
                "anchor.emitter" -> plan.anchorLink = BatchOptions.text(token.value)
                "sample.count" -> {
                    plan.sampling = CommandParticleRecipes.Sampling.PARAMETER
                    plan.range = null
                    plan.count = BatchOptions.int(token.value, token.key).also {
                        requireLocalized(it in 1..ParticleRecipe.MAX_SEEDS) {
                            translation("error.batch.range", token.key, 1, ParticleRecipe.MAX_SEEDS)
                        }
                    }
                }
                "sample.range" -> {
                    val values = BatchOptions.splitList(token.value)
                    BatchOptions.requireCount(values, 3, token.key)
                    plan.sampling = CommandParticleRecipes.Sampling.PARAMETER
                    val range = Triple(
                        BatchOptions.double(values[0], token.key),
                        BatchOptions.double(values[1], token.key),
                        BatchOptions.double(values[2], token.key),
                    )
                    ParticleSampling.range(range.first, range.second, range.third)
                    plan.range = range
                }
                "sample.grid" -> plan.sampling = CommandParticleRecipes.Sampling.GRID
                "sample.bounds" -> {
                    val values = BatchOptions.splitList(token.value)
                    BatchOptions.requireCount(values, 6, token.key)
                    plan.sampling = CommandParticleRecipes.Sampling.GRID
                    plan.min = Vec3(
                        BatchOptions.double(values[0], token.key),
                        BatchOptions.double(values[1], token.key),
                        BatchOptions.double(values[2], token.key),
                    )
                    plan.max = Vec3(
                        BatchOptions.double(values[3], token.key),
                        BatchOptions.double(values[4], token.key),
                        BatchOptions.double(values[5], token.key),
                    )
                }
                "sample.step" -> {
                    plan.sampling = CommandParticleRecipes.Sampling.GRID
                    plan.step = BatchOptions.double(token.value, token.key).also {
                        requireLocalized(it > 0.0) { translation("error.batch.positive", token.key) }
                    }
                }
                "sample.image" -> {
                    plan.image = CommandParticleRecipes.SampleAsset(BatchOptions.text(token.value), resource = false)
                    plan.sampling = CommandParticleRecipes.Sampling.IMAGE
                }
                "sample.mesh" -> {
                    plan.mesh = CommandParticleRecipes.SampleAsset(BatchOptions.text(token.value), resource = false)
                    plan.sampling = CommandParticleRecipes.Sampling.MESH
                }
                "sample.mesh_count" -> {
                    plan.meshOptionsTouched = true
                    plan.meshCount = BatchOptions.int(token.value, token.key).also {
                        requireLocalized(it in 1..ParticleRecipe.MAX_SEEDS) {
                            translation("error.batch.range", token.key, 1, ParticleRecipe.MAX_SEEDS)
                        }
                    }
                }
                "sample.mesh_seed" -> {
                    plan.meshOptionsTouched = true
                    plan.meshSeed = BatchOptions.int(token.value, token.key)
                }
                "sample.text" -> {
                    plan.sampling = CommandParticleRecipes.Sampling.TEXT
                    plan.text = BatchOptions.text(token.value).replace('|', '\n')
                }
                "sample.text_count" -> plan.textCount = BatchOptions.int(token.value, token.key).also {
                    requireLocalized(it in 1..ParticleRecipe.MAX_SEEDS) {
                        translation("error.batch.range", token.key, 1, ParticleRecipe.MAX_SEEDS)
                    }
                }
                "sample.text_height" -> plan.textHeight = BatchOptions.double(token.value, token.key).also {
                    requireLocalized(it > 0.0) { translation("error.batch.positive", token.key) }
                }
                "sample.stride" -> {
                    plan.imageOptionsTouched = true
                    plan.imageOptions = plan.imageOptions.copy(stride = BatchOptions.int(token.value, token.key))
                }
                "sample.plane" -> {
                    plan.imageOptionsTouched = true
                    val plane = BatchOptions.value(token, listOf("xy", "xz", "yz"))
                    plan.imageOptions = plan.imageOptions.copy(plane = ParticleImageSampler.Plane.valueOf(plane.uppercase()))
                }
                "sample.spacing" -> {
                    plan.imageOptionsTouched = true
                    plan.imageOptions = plan.imageOptions.copy(spacing = BatchOptions.double(token.value, token.key))
                }
                "sample.alpha" -> {
                    plan.imageOptionsTouched = true
                    plan.imageOptions = plan.imageOptions.copy(alpha = BatchOptions.double(token.value, token.key))
                }
                else -> apply(plan, token)
            }
        }
        if (plan.anchorPos == null && plan.anchorKind != "entity" && plan.anchorKind != "emitter") {
            plan.anchorPos = source.position
        }
        validateSampling(plan)
        return plan
    }

    private fun apply(plan: Plan, token: BatchToken) {
        val key = token.key
        val value = token.value
        when {
            key.startsWith("let.") -> plan.recipe = plan.recipe.copy(
                lets = plan.recipe.lets + (key.substringAfter('.') to BatchOptions.formula(value)),
            )
            key.startsWith("birth.") -> {
                val field = key.substringAfter('.')
                requireLocalized(field in RecipeExpressions.OUTPUTS) { translation("error.batch.expression_field", key) }
                plan.recipe = plan.recipe.copy(birth = plan.recipe.birth + (field to BatchOptions.formula(value)))
            }
            key.startsWith("motion.") -> {
                val field = key.substringAfter('.')
                requireLocalized(field in RecipeExpressions.OUTPUTS) { translation("error.batch.expression_field", key) }
                plan.recipe = plan.recipe.copy(motion = plan.recipe.motion + (field to BatchOptions.formula(value)))
            }
            key.startsWith("variable.") -> plan.recipe = plan.recipe.copy(
                variables = plan.recipe.variables + (key.substringAfter('.') to BatchOptions.double(value, key)),
            )
            key.startsWith("state.") -> plan.recipe = plan.recipe.copy(
                state = plan.recipe.state + (key.substringAfter('.') to BatchOptions.double(value, key)),
            )
            key.startsWith("appearance.") -> {
                val field = key.substringAfter('.')
                requireLocalized(field in ParticleCommandOptionParser.appearanceKeys) {
                    translation("error.recipe.appearance_key", field)
                }
                plan.appearance[field] = BatchOptions.text(value)
                plan.appearanceDirty = true
            }
            key == "where" -> plan.recipe = plan.recipe.copy(where = BatchOptions.formula(value))
            key == "kill" -> plan.recipe = plan.recipe.copy(kill = BatchOptions.formula(value))
            key == "select" -> plan.recipe = plan.recipe.copy(where = BatchOptions.formula(value))
            key == "orbit" -> plan.recipe = plan.recipe.copy(orbit = BatchOptions.formula(value))
            key == "emitter.lifetime" -> plan.recipe = plan.recipe.copy(lifetime = BatchOptions.int(value, key))
            key == "emitter.emit_from" -> plan.recipe = plan.recipe.copy(emitFromTick = BatchOptions.int(value, key))
            key == "emitter.emit_until" -> plan.recipe = plan.recipe.copy(emitUntilTick = BatchOptions.int(value, key))
            key == "rate" -> plan.recipe = plan.recipe.copy(rate = BatchOptions.int(value, key))
            key == "interval" -> plan.recipe = plan.recipe.copy(interval = BatchOptions.int(value, key))
            key == "max_alive" -> plan.recipe = plan.recipe.copy(maxAlive = BatchOptions.int(value, key))
            key == "spacing" -> plan.recipe = plan.recipe.copy(spacing = BatchOptions.double(value, key))
            key == "simulation_range" -> plan.recipe = plan.recipe.copy(simulationRange = BatchOptions.double(value, key))
            key == "simulation_range.preserve" -> plan.recipe = plan.recipe.copy(preserveSimulation = BatchOptions.bool(value, key))
            key == "follow" -> plan.recipe = plan.recipe.copy(follow = BatchOptions.bool(value, key))
            key == "collide" -> plan.recipe = plan.recipe.copy(
                collision = when (BatchOptions.value(token, listOf("none", "solid", "liquid", "both", "entity", "all"))) {
                    "none" -> RecipeCollision.NONE
                    "solid" -> RecipeCollision.SOLID
                    "liquid" -> RecipeCollision.LIQUID
                    "both" -> RecipeCollision.SOLID_AND_LIQUID
                    "entity" -> RecipeCollision.ENTITY
                    else -> RecipeCollision.ALL
                },
            )
            key == "collision.size" -> plan.recipe = plan.recipe.copy(collisionHalfSize = BatchOptions.double(value, key))
            key == "collision.steps" -> plan.recipe = plan.recipe.copy(collisionSteps = BatchOptions.int(value, key))
            key == "collision.bounce" -> plan.recipe = plan.recipe.copy(bounce = BatchOptions.double(value, key))
            key == "collision.friction" -> plan.recipe = plan.recipe.copy(friction = BatchOptions.double(value, key))
            key == "subemit" -> {
                val on = BatchOptions.value(token, listOf("on", "off")) == "on"
                plan.recipe = plan.recipe.copy(subEmit = if (on) (plan.recipe.subEmit ?: SubEmit()) else null)
            }
            key == "subemit.count" -> plan.recipe = plan.recipe.copy(subEmit = (plan.recipe.subEmit ?: SubEmit()).copy(count = BatchOptions.int(value, key)))
            key == "subemit.lifetime" -> plan.recipe = plan.recipe.copy(subEmit = (plan.recipe.subEmit ?: SubEmit()).copy(lifetime = BatchOptions.int(value, key)))
            key == "subemit.speed" -> plan.recipe = plan.recipe.copy(subEmit = (plan.recipe.subEmit ?: SubEmit()).copy(speed = BatchOptions.double(value, key)))
            key == "subemit.scale" -> plan.recipe = plan.recipe.copy(subEmit = (plan.recipe.subEmit ?: SubEmit()).copy(scale = BatchOptions.double(value, key)))
            key == "unset" -> unset(plan, BatchOptions.text(value))
            else -> throw LocalizedArgumentException(translation("error.batch.unknown_key", key, "emitter"))
        }
    }

    private fun unset(plan: Plan, raw: String) {
        val section = raw.substringBefore('.')
        val field = raw.substringAfter('.', "")
        when (section) {
            "variable" -> plan.recipe = plan.recipe.copy(variables = plan.recipe.variables - field)
            "state" -> plan.recipe = plan.recipe.copy(state = plan.recipe.state - field)
            "let" -> plan.recipe = plan.recipe.copy(lets = plan.recipe.lets - field)
            "birth" -> plan.recipe = plan.recipe.copy(birth = plan.recipe.birth - field)
            "motion" -> plan.recipe = plan.recipe.copy(motion = plan.recipe.motion - field)
            "where" -> plan.recipe = plan.recipe.copy(where = "true")
            "kill" -> plan.recipe = plan.recipe.copy(kill = "false")
            "orbit" -> plan.recipe = plan.recipe.copy(orbit = "")
            "subemit" -> plan.recipe = plan.recipe.copy(subEmit = null)
            "appearance" -> {
                if (field.isEmpty()) plan.appearance.clear() else {
                    requireLocalized(field in ParticleCommandOptionParser.appearanceKeys) {
                        translation("error.recipe.appearance_key", field)
                    }
                    plan.appearance.remove(field)
                }
                plan.appearanceDirty = true
            }
            else -> throw LocalizedArgumentException(translation("error.recipe.section"))
        }
    }

    private fun validateSampling(plan: Plan) {
        val modes = ArrayList<String>()
        plan.range?.let { modes += "range" }
        if (plan.sampling == CommandParticleRecipes.Sampling.GRID) modes += "grid"
        plan.image?.let { modes += "image" }
        if (plan.sampling == CommandParticleRecipes.Sampling.TEXT) modes += "text"
        plan.mesh?.let { modes += "mesh" }
        requireLocalized(modes.size <= 1) { translation("error.batch.sampling_conflict", modes.joinToString(", ")) }
        requireLocalized(!plan.imageOptionsTouched || plan.sampling == CommandParticleRecipes.Sampling.IMAGE) {
            translation("error.batch.image_options")
        }
        requireLocalized(!plan.meshOptionsTouched || plan.sampling == CommandParticleRecipes.Sampling.MESH) {
            translation("error.batch.mesh_options")
        }
        if (modes.isNotEmpty()) plan.count = 1
    }

    /** 校验：公式、采样、锚点链接、配置数量上限都在这里一次做完。 */
    fun validate(plan: Plan, level: ServerLevel, dimension: UUID, name: String, existing: CommandParticleRecipes.Draft?) {
        plan.recipe.copy(seeds = plan.sample(level)).validate()
        if (plan.appearanceDirty) appearanceOptions(plan.appearance)
        requireLocalized(existing != null || CommandParticleRecipes.names(dimension).size < 256) {
            translation("error.recipe.config_limit")
        }
        when (plan.anchorKind) {
            "emitter" -> {
                val target = requireNotNullLocalized(plan.anchorLink) { translation("error.batch.anchor_target") }
                val seen = hashSetOf(name)
                var current: String? = target
                while (current != null) {
                    requireLocalized(seen.add(current)) { translation("error.recipe.anchor_cycle") }
                    current = CommandParticleRecipes.get(dimension, current).anchorLink
                }
            }
            "entity" -> resolveEntity(requireNotNullLocalized(plan.anchorEntity) { translation("error.batch.anchor_entity") }, level)
        }
    }

    fun resolveEntity(raw: String, level: ServerLevel): net.minecraft.world.entity.Entity {
        val reader = StringReader(raw)
        val selector = try {
            EntityArgument.entity().parse(reader)
        } catch (error: CommandSyntaxException) {
            throw LocalizedArgumentException(translation("error.batch.selector", raw))
        }
        if (reader.canRead()) throw LocalizedArgumentException(translation("error.batch.selector", raw))
        return try {
            selector.findSingleEntity(level.server.createCommandSourceStack())
        } catch (error: CommandSyntaxException) {
            throw LocalizedArgumentException(translation("error.batch.selector", raw))
        }
    }

    /** 写入配置并执行 mode。调用前必须已经过 [validate]。 */
    fun commit(
        name: String, plan: Plan, level: ServerLevel, dimension: UUID,
        existing: CommandParticleRecipes.Draft?, source: CommandSourceStack,
    ): CommandParticleRecipes.Draft {
        val draft = if (plan.keep && existing != null) {
            existing
        } else {
            val anchor = anchorFor(plan, level, source)
            if (existing != null) CommandParticleRecipes.reset(dimension, name, anchor)
            else CommandParticleRecipes.create(dimension, name, anchor)
        }
        if (plan.anchorKind != "keep") applyAnchor(draft, name, plan, level, dimension)
        draft.sampling = plan.sampling
        draft.count = plan.count
        draft.range = plan.range
        draft.min = plan.min
        draft.max = plan.max
        draft.step = plan.step
        draft.image = plan.image
        draft.mesh = plan.mesh
        draft.text = plan.text
        draft.textCount = plan.textCount
        draft.textHeight = plan.textHeight
        draft.meshCount = plan.meshCount
        draft.meshSeed = plan.meshSeed
        draft.imageOptions = plan.imageOptions
        draft.update(plan.recipe.copy(seeds = plan.sample(level)))
        if (plan.appearanceDirty) {
            val parsed = appearanceOptions(plan.appearance)
            var birth = draft.recipe.birth
            if ("color" in plan.appearance) {
                val color = ParticleCommandOptionParser.parseColor(plan.appearance.getValue("color"))
                birth = birth + mapOf(
                    "r" to color.r.toString(), "g" to color.g.toString(), "b" to color.b.toString(), "a" to color.a.toString(),
                )
            }
            if ("scale" in plan.appearance) birth = birth + ("sc" to parsed.scale.toString())
            draft.update(draft.recipe.copy(visual = parsed.visual, lifeCurve = parsed.lifeCurve, birth = birth))
            draft.appearance.clear()
            draft.appearance.putAll(plan.appearance)
        }
        when (plan.mode) {
            "start" -> draft.start(level, burst = false)
            "burst" -> draft.start(level, burst = true)
            "start_instanced" -> draft.start(level, burst = false, mode = ParticleRecipeMode.INSTANCED)
            "burst_instanced" -> draft.start(level, burst = true, mode = ParticleRecipeMode.INSTANCED)
            "burst_gpu" -> draft.start(level, burst = true, mode = ParticleRecipeMode.GPU)
            "start_synced" -> draft.start(level, burst = false, synced = true)
            "burst_synced" -> draft.start(level, burst = true, synced = true)
            "config" -> Unit
        }
        return draft
    }

    private fun applyAnchor(
        draft: CommandParticleRecipes.Draft, name: String, plan: Plan, level: ServerLevel, dimension: UUID,
    ) {
        when (plan.anchorKind) {
            "emitter" -> CommandParticleRecipes.linkAnchor(dimension, name, requireNotNullLocalized(plan.anchorLink) { translation("error.batch.anchor_target") })
            "entity" -> {
                val entity = resolveEntity(
                    requireNotNullLocalized(plan.anchorEntity) { translation("error.batch.anchor_entity") }, level,
                )
                CommandParticleRecipes.anchor(
                    dimension, name,
                    RecipeAnchor(entity.position(), entity.uuid, entity.id, plan.anchorOffset, plan.anchorLocal),
                )
            }
            "keep" -> Unit
            else -> CommandParticleRecipes.anchor(dimension, name, RecipeAnchor(requireNotNullLocalized(plan.anchorPos) { translation("error.batch.anchor_pos") }))
        }
    }

    private fun anchorFor(plan: Plan, level: ServerLevel, source: CommandSourceStack): RecipeAnchor = when (plan.anchorKind) {
        "entity" -> {
            val entity = resolveEntity(requireNotNullLocalized(plan.anchorEntity) { translation("error.batch.anchor_entity") }, level)
            RecipeAnchor(entity.position(), entity.uuid, entity.id, plan.anchorOffset, plan.anchorLocal)
        }
        "emitter" -> RecipeAnchor(source.position)
        else -> RecipeAnchor(plan.anchorPos ?: source.position)
    }

    private fun appearanceOptions(map: Map<String, String>): ParticleCommandOptions {
        val text = map.entries.joinToString(" ") {
            "${it.key}=\"${it.value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        val parsed = ParticleCommandOptionParser.parse(text)
        requireLocalized(parsed.provided == map.keys) { translation("error.recipe.appearance_value") }
        return parsed
    }
}

private fun Vec3.toCore() = CoreVec3(x, y, z)

/** 把位置分量按命令源解析成世界坐标。 */
internal fun List<BatchCoord>.resolve(source: CommandSourceStack): Vec3 = Vec3(
    resolveComponent(0, source),
    resolveComponent(1, source),
    resolveComponent(2, source),
)

private fun List<BatchCoord>.resolveComponent(index: Int, source: CommandSourceStack): Double = when (val coord = this[index]) {
    is BatchCoord.Absolute -> coord.value
    is BatchCoord.Relative -> when (index) {
        0 -> source.position.x; 1 -> source.position.y; else -> source.position.z
    } + coord.value
    is BatchCoord.Local -> throw LocalizedArgumentException(translation("error.batch.local_coord"))
}


