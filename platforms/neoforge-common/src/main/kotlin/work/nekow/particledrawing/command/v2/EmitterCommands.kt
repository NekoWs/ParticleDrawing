package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.api.ParticleSourceHandle
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.command.CommandNamePattern
import work.nekow.particledrawing.command.CommandParticleRecipes
import work.nekow.particledrawing.command.CommandParticleRecipes.Draft
import work.nekow.particledrawing.command.v2.EmitterDeclaration.Companion.prepareRecipe
import work.nekow.particledrawing.core.server.ServerParticleSources
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

internal object EmitterCommands {
    private data class Template(val declaration: EmitterDeclaration?, val snapshot: Draft?)
    private val templates = hashMapOf<UUID, LinkedHashMap<String, Template>>()

    private data class Launch(val burst: Boolean, val mode: String) {
        val label get() = if (burst) "burst" else "start"

        fun prepare(source: CommandSourceStack, draft: Draft, replacing: Set<UUID> = emptySet()): () -> UUID =
            ServerParticleSources.prepareStart(source.level, draft.recipe, draft.anchor, burst, mode == "synced",
                when (mode) {
                    "instanced" -> ParticleRecipeMode.INSTANCED
                    "gpu" -> ParticleRecipeMode.GPU
                    else -> ParticleRecipeMode.STANDARD
                }, replacing)
    }

    fun parse(op: String, input: CommandInput, source: CommandSourceStack): () -> Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        val names = CommandParticleRecipes.names(dimension)
        return when (op) {
            "emitter" -> {
                val name = input.name(names)
                val declaration = EmitterDeclaration.parse(input, names)
                val launch = input.launch()
                val dryrun = input.end()
                command {
                    val draft = fresh(source)
                    declaration.apply(draft, EmitterDeclaration.Binding(source, name))
                    install(source, dimension, name, draft, launch, dryrun)
                }
            }
            "template" -> {
                val name = input.name(templates[dimension]?.keys.orEmpty())
                val copied = if (input.take("from")) {
                    input.expect("emitter")
                    input.name(names)
                } else null
                val declaration = if (copied == null) EmitterDeclaration.parse(input, names) else null
                val dryrun = input.end()
                command {
                    val snapshot = copied?.let { CommandParticleRecipes.get(dimension, it).copyDraft() }
                    if (declaration != null) {
                        val preview = fresh(null)
                        declaration.apply(preview, EmitterDeclaration.Binding(null, name))
                        preview.prepareRecipe()
                        if (preview.sampling == CommandParticleRecipes.Sampling.PARAMETER ||
                            preview.sampling == CommandParticleRecipes.Sampling.GRID) preview.samples(source.level)
                    }
                    val entries = templates[dimension].orEmpty()
                    requireLocalized(name in entries || entries.size < 256) { translation("error.command.v2.template_limit") }
                    if (!dryrun) templates.getOrPut(dimension) { linkedMapOf() }[name] = Template(declaration, snapshot)
                    success(source, name, if (dryrun) "dryrun" else "template")
                }
            }
            "use" -> {
                val template = input.name(templates[dimension]?.keys.orEmpty())
                input.expect("as")
                val name = input.name(names)
                val without = EmitterDeclaration.without(input)
                val declaration = EmitterDeclaration.parse(input, names)
                val launch = input.launch()
                val dryrun = input.end()
                command {
                    val binding = EmitterDeclaration.Binding(source, name)
                    val draft = resolveTemplate(source, template, binding)
                    without.apply(draft, binding)
                    declaration.apply(draft, binding)
                    install(source, dimension, name, draft, launch, dryrun)
                }
            }
            "start", "burst" -> {
                val name = input.name(names)
                val launch = input.mode(op == "burst")
                val dryrun = input.end()
                command {
                    val draft = CommandParticleRecipes.get(dimension, name)
                    requireLocalized(launch.burst || draft.activeHandles().none { ServerParticleSources.isContinuouslyEmitting(it.id) }) {
                        translation("error.recipe.running")
                    }
                    val prepared = draft.copyDraft()
                    prepared.recipe = prepared.recipe.copy(seeds = prepared.samples(source.level))
                    val start = launch.prepare(source, prepared)
                    if (!dryrun) {
                        draft.record(ParticleSourceHandle(start(), source.level))
                        draft.recipe = prepared.recipe
                    }
                    result(source, name, prepared, launch, dryrun)
                }
            }
            "stop" -> {
                val pattern = input.namePattern(names)
                input.finish()
                command {
                    val targets = pattern.select(CommandParticleRecipes.names(dimension))
                    targets.forEach { name -> CommandParticleRecipes.get(dimension, name).activeHandles().forEach { it.stop() } }
                    success(source, pattern, "stop", targets.size)
                }
            }
            "advanced" -> advanced(input, source, dimension, names)
            else -> throw work.nekow.particledrawing.i18n.LocalizedStateException(translation("error.command.v2.failed"))
        }
    }

    fun templateNames(dimension: UUID): Set<String> = templates[dimension]?.keys.orEmpty()

    fun bindingRecipe(source: CommandSourceStack, name: String): ParticleRecipe {
        val draft = resolveTemplate(source, name, EmitterDeclaration.Binding(source, null), useAnchor = false)
        draft.recipe = draft.recipe.copy(seeds = draft.samples(source.level))
        draft.prepareRecipe()
        return draft.recipe
    }

    private fun resolveTemplate(
        source: CommandSourceStack, name: String, binding: EmitterDeclaration.Binding, useAnchor: Boolean = true,
    ): Draft {
        val saved = requireNotNullLocalized(templates[ParticleUtils.dimensionUUID(source.level)]?.get(name)) {
            translation("error.command.v2.template_missing", name)
        }
        return (saved.snapshot?.copyDraft() ?: fresh(source)).also {
            saved.declaration?.apply(it, binding, useAnchor)
        }
    }

    private fun command(action: () -> Int) = action

    private fun CommandInput.launch(): Launch? = when {
        take("start") -> mode(false)
        take("burst") -> mode(true)
        else -> null
    }

    private fun CommandInput.mode(burst: Boolean): Launch =
        Launch(burst, (listOf("standard", "instanced", "synced") + if (burst) listOf("gpu") else emptyList())
            .firstOrNull(::take) ?: "standard")

    private fun fresh(source: CommandSourceStack?) = Draft(
        source?.let { RecipeAnchor(it.position) } ?: RecipeAnchor(Vec3.ZERO),
    )

    private fun anchorUpdates(dimension: UUID, name: String, draft: Draft): List<Pair<Draft, RecipeAnchor>> {
        val values = CommandParticleRecipes.resolveAnchors(dimension, name, draft)
        draft.anchor = values.getValue(name)
        return CommandParticleRecipes.snapshot(dimension).mapNotNull { (key, existing) ->
            val anchor = values.getValue(key)
            if (key == name || existing.anchor == anchor) null else {
                existing.activeHandles().forEach { ServerParticleSources.checkAnchor(it.id, anchor) }
                existing to anchor
            }
        }
    }

    private fun updateAnchors(updates: List<Pair<Draft, RecipeAnchor>>) {
        for ((draft, anchor) in updates) {
            draft.activeHandles().forEach { it.anchor(anchor) }
            draft.anchor = anchor
        }
    }

    private fun install(
        source: CommandSourceStack, dimension: UUID, name: String, draft: Draft,
        launch: Launch?, dryrun: Boolean,
    ): Int {
        CommandParticleRecipes.checkCapacity(dimension, name)
        val existing = CommandParticleRecipes.find(dimension, name)
        val updates = anchorUpdates(dimension, name, draft)
        draft.recipe = draft.recipe.copy(seeds = draft.samples(source.level))
        draft.prepareRecipe()
        val start = launch?.prepare(source, draft, existing?.activeHandles().orEmpty().map { it.id }.toSet())
        if (!dryrun) {
            existing?.removeParticles()
            CommandParticleRecipes.install(dimension, name, draft)
            updateAnchors(updates)
            if (start != null) draft.record(ParticleSourceHandle(start(), source.level))
        }
        return result(source, name, draft, launch, dryrun)
    }

    private fun advanced(
        input: CommandInput, source: CommandSourceStack, dimension: UUID, names: List<String>,
    ): () -> Int {
        if (input.take("list")) {
            input.finish()
            return { success(source, names.joinToString(", "), "list") }
        }
        val name = input.name(names)
        return when (val op = input.choice(listOf("inspect", "edit", "apply", "remove_selected"))) {
            "inspect" -> {
                input.finish()
                command {
                    val draft = CommandParticleRecipes.get(dimension, name)
                    source.reply({
                        translation("command.v2.inspect", name, draft.sampling.name.lowercase(), draft.recipe.seeds.size,
                            draft.recipe.lifetime, draft.recipe.rate, draft.recipe.maxAlive, draft.activeHandles().size,
                            draft.anchorLink ?: draft.anchor.position).component()
                    })
                    1
                }
            }
            "edit" -> {
                val without = EmitterDeclaration.without(input)
                val declaration = EmitterDeclaration.parse(input, names)
                if (without.empty && declaration.empty) input.invalid("configuration changes")
                val dryrun = input.end()
                command {
                    val existing = CommandParticleRecipes.get(dimension, name)
                    val draft = existing.copyDraft()
                    val binding = EmitterDeclaration.Binding(source, name)
                    without.apply(draft, binding)
                    declaration.apply(draft, binding)
                    val updates = anchorUpdates(dimension, name, draft)
                    if (without.changesSampling || declaration.changesSampling) {
                        draft.recipe = draft.recipe.copy(seeds = draft.samples(source.level))
                    }
                    draft.prepareRecipe()
                    val handles = existing.activeHandles()
                    if (draft.recipe != existing.recipe) handles.forEach { ServerParticleSources.checkUpdate(it.id, draft.recipe, draft.anchor) }
                    if (draft.anchor != existing.anchor) handles.forEach { ServerParticleSources.checkAnchor(it.id, draft.anchor) }
                    if (!dryrun) {
                        for (handle in handles) {
                            if (draft.recipe != existing.recipe) handle.update(draft.recipe)
                            if (draft.anchor != existing.anchor) handle.anchor(draft.anchor)
                            draft.record(handle)
                        }
                        CommandParticleRecipes.install(dimension, name, draft)
                        updateAnchors(updates)
                    }
                    result(source, name, draft, null, dryrun)
                }
            }
            else -> {
                val formula = if (input.take("where")) input.formula("where") else null
                input.finish()
                command {
                    val draft = CommandParticleRecipes.get(dimension, name)
                    val predicate = formula ?: draft.selector
                    val remove = op == "remove_selected"
                    val edits = if (remove) emptyMap() else draft.changes
                    draft.recipe.copy(birth = edits, where = predicate).validate()
                    val handles = draft.activeHandles()
                    handles.forEach { ServerParticleSources.checkEdit(it.id, predicate, edits) }
                    for (handle in handles) {
                        if (remove) handle.removeWhere(predicate) else handle.changeWhere(predicate, edits)
                    }
                    success(source, name, op)
                }
            }
        }
    }

    fun control(source: CommandSourceStack, op: String, kind: String, input: CommandInput): () -> Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        val names = if (kind == "template") templates[dimension]?.keys.orEmpty() else CommandParticleRecipes.names(dimension)
        val pattern = input.namePattern(names)
        input.finish()
        return {
            val targets = pattern.select(if (kind == "template") templateNames(dimension) else CommandParticleRecipes.names(dimension))
            when {
                kind == "template" -> targets.forEach { name ->
                    requireLocalized(templates[dimension]?.remove(name) != null) { translation("error.command.v2.template_missing", name) }
                }
                op == "delete" -> CommandParticleRecipes.remove(dimension, targets)
                else -> targets.forEach { CommandParticleRecipes.get(dimension, it).removeParticles() }
            }
            success(source, pattern, op, targets.size)
        }
    }

    internal fun success(source: CommandSourceStack, pattern: CommandNamePattern, op: String, count: Int): Int =
        if (pattern.isWildcard) pattern.report(source, count) else success(source, pattern.text, op)

    private fun result(source: CommandSourceStack, name: String, draft: Draft, launch: Launch?, dryrun: Boolean): Int {
        val action = when {
            launch == null -> translation("command.v2.action.config")
            launch.burst -> translation("command.v2.action.burst")
            else -> translation("command.v2.action.start")
        }
        source.reply({
            translation("command.v2.emitter", action, name, draft.recipe.seeds.size,
                if (dryrun) translation("command.v2.dryrun") else "").component()
        })
        return 1
    }

    internal fun success(source: CommandSourceStack, name: String, op: String): Int {
        val key = when (op) {
            "start" -> "command.v2.op.start"
            "burst" -> "command.v2.op.burst"
            "stop" -> "command.v2.op.stop"
            "clear" -> "command.v2.op.clear"
            "delete" -> "command.v2.op.delete"
            "template" -> "command.v2.op.template"
            "list" -> "command.v2.op.list"
            "edit" -> "command.v2.op.edit"
            "apply" -> "command.v2.op.apply"
            "remove_selected" -> "command.v2.op.remove_selected"
            "dryrun" -> "command.v2.op.dryrun"
            else -> "command.v2.done"
        }
        source.reply({
            if (key == "command.v2.done") translation(key, name, op).component()
            else translation(key, name).component()
        })
        return 1
    }

    fun discard(dimension: UUID) { templates.remove(dimension) }
    fun clear() { templates.clear() }
}
