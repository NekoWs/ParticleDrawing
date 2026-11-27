package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import work.nekow.particledrawing.api.EntityParticleBurst
import work.nekow.particledrawing.api.EntityParticleEffects
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.core.server.ServerEntityParticleEffects
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.util.ParticleUtils

internal object EntityBindingCommands {
    private data class Event(val template: String, val mode: ParticleRecipeMode, val each: Boolean, val stop: Boolean)

    fun parse(verb: String, input: CommandInput, source: CommandSourceStack): () -> Int {
        if (verb == "unbind") {
            val choices = source.entity?.let(ServerEntityParticleEffects::names).orEmpty()
            val pattern = input.namePattern(choices)
            input.finish()
            return {
                val entity = entity(source)
                val count = pattern.select(ServerEntityParticleEffects.names(entity)).count { EntityParticleEffects.unbind(entity, it) }
                if (pattern.isWildcard) pattern.report(source, count) else count
            }
        }

        val name = input.name()
        val names = EmitterCommands.templateNames(ParticleUtils.dimensionUUID(source.level))
        val emission = if (input.take("emit")) input.name(names) else null
        val emissionMode = if (emission == null) ParticleRecipeMode.STANDARD else input.mode(burst = false)
        val events = linkedMapOf<ResourceId, Event>()
        while (input.take("on")) {
            val raw = input.value("event", listOf("impact", "death"))
            val id = when (raw) {
                "impact" -> EntityParticleEffects.IMPACT
                "death" -> EntityParticleEffects.DEATH
                else -> ResourceId.tryParse(raw) ?: input.invalid("event identifier", raw)
            }
            if (id in events) input.invalid("distinct event", raw)
            input.expect("burst")
            val template = input.name(names)
            val mode = input.mode(burst = true)
            events[id] = Event(template, mode, input.take("each"), input.take("stop"))
        }
        if (emission == null && events.isEmpty()) input.invalid("emit|on")
        input.finish()
        return {
            val entity = entity(source)
            if (!EntityParticleEffects.isBound(entity, name)) {
                EntityParticleEffects.bind(
                    entity, name, emission?.let { EmitterCommands.bindingRecipe(source, it) },
                    events.mapValues { (_, event) ->
                        EntityParticleBurst(
                            EmitterCommands.bindingRecipe(source, event.template), event.each, event.stop, event.mode,
                        )
                    },
                    emissionMode,
                )
            }
            1
        }
    }

    private fun entity(source: CommandSourceStack) =
        requireNotNullLocalized(source.entity) { translation("error.binding.executor") }.also {
            requireLocalized(it.level() === source.level) { translation("error.binding.dimension") }
        }

    private fun CommandInput.mode(burst: Boolean): ParticleRecipeMode {
        if (!take("mode")) return ParticleRecipeMode.STANDARD
        val modes = listOf("standard", "instanced") + if (burst) listOf("gpu") else emptyList()
        return when (choice(modes)) {
            "instanced" -> ParticleRecipeMode.INSTANCED
            "gpu" -> ParticleRecipeMode.GPU
            else -> ParticleRecipeMode.STANDARD
        }
    }
}
