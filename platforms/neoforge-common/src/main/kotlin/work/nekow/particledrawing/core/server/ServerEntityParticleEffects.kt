package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.recipe.ParticleRecipeExecution
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.EntityParticleBurst
import work.nekow.particledrawing.api.EntityParticleEffects
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.api.ParticleSourceHandle
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId

@EventBusSubscriber(modid = ParticleDrawing.MODID)
internal object ServerEntityParticleEffects {
    private class Binding(var source: ParticleSourceHandle?, val events: MutableMap<ResourceId, EntityParticleBurst>) {
        fun stop() {
            source?.stop()
            source = null
        }
    }

    private const val MAX_BINDINGS = 1024
    private const val MAX_EVENTS = 16
    private val bindings = hashMapOf<Entity, MutableMap<String, Binding>>()
    private var bindingCount = 0
    private val logger = LogManager.getLogger("ParticleDrawing")

    fun isBound(entity: Entity, name: String) = entity.level() is ServerLevel && bindings[entity]?.containsKey(name) == true

    fun names(entity: Entity): List<String> =
        if (entity.level() is ServerLevel) bindings[entity]?.keys?.toList().orEmpty() else emptyList()

    fun bind(
        entity: Entity, name: String, emission: ParticleRecipe?, events: Map<ResourceId, EntityParticleBurst>,
        emissionMode: ParticleRecipeMode,
    ): Boolean {
        val level = requireNotNullLocalized(entity.level() as? ServerLevel) { translation("error.binding.executor") }
        if (isBound(entity, name)) return false
        requireLocalized(!entity.isRemoved && name.isNotBlank() && name.length <= 64 &&
            (emission != null || events.isNotEmpty()) && events.size <= MAX_EVENTS) { translation("error.binding.invalid") }
        requireLocalized(bindingCount < MAX_BINDINGS) { translation("error.binding.limit", MAX_BINDINGS) }
        requireLocalized((emission == null || emission.lifetime > 0) && events.values.all { it.recipe.lifetime > 0 }) {
            translation("error.binding.lifetime")
        }
        val anchor = RecipeAnchor(entity.position())
        val frozen = events.mapValuesTo(linkedMapOf()) { (_, burst) ->
            val recipe = ServerParticleSources.freeze(burst.recipe)
            recipe.validate()
            RecipeRuntime.requireCadence(recipe, burst = true)
            ParticleRecipeExecution.validate(recipe, anchor, burst = true, mode = burst.mode)
            burst.copy(recipe = recipe)
        }
        val source = emission?.let {
            val followed = RecipeAnchor(entity.position(), entity.uuid, entity.id)
            ParticleSourceHandle(ServerParticleSources.start(level, it, followed, burst = false, mode = emissionMode), level)
        }
        bindings.getOrPut(entity) { linkedMapOf() }[name] = Binding(source, frozen)
        bindingCount++
        return true
    }

    fun unbind(entity: Entity, name: String): Boolean {
        if (entity.level() !is ServerLevel) return false
        val named = bindings[entity] ?: return false
        val binding = named.remove(name) ?: return false
        if (named.isEmpty()) bindings.remove(entity)
        bindingCount--
        binding.stop()
        return true
    }

    fun trigger(entity: Entity, event: ResourceId, position: Vec3): Int {
        val level = entity.level() as? ServerLevel ?: return 0
        val named = bindings[entity] ?: return 0
        requireLocalized(position.x.isFinite() && position.y.isFinite() && position.z.isFinite()) {
            translation("error.render.position_range")
        }
        val anchor = RecipeAnchor(position)
        var started = 0
        for (binding in named.values) {
            val burst = binding.events[event] ?: continue
            if (!burst.each) binding.events.remove(event)
            if (burst.stopEmission) binding.stop()
            try {
                ServerParticleSources.start(level, burst.recipe, anchor, burst = true, mode = burst.mode)
                started++
            } catch (error: IllegalArgumentException) {
                logger.debug("Could not start {} particle burst for {}: {}", event, entity.uuid, error.message)
            }
        }
        return started
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    @JvmStatic
    fun onImpact(event: ProjectileImpactEvent) {
        val hit = event.rayTraceResult
        if (!event.isCanceled && hit.type != HitResult.Type.MISS) {
            trigger(event.projectile, EntityParticleEffects.IMPACT, hit.location)
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    @JvmStatic
    fun onDeath(event: LivingDeathEvent) {
        if (!event.isCanceled) trigger(event.entity, EntityParticleEffects.DEATH, event.entity.position())
    }

    @SubscribeEvent
    @JvmStatic
    fun onEntityLeave(event: EntityLeaveLevelEvent) {
        if (event.level !is ServerLevel) return
        val removed = bindings.remove(event.entity) ?: return
        bindingCount -= removed.size
        removed.values.forEach(Binding::stop)
    }

    fun discard(level: ServerLevel) {
        bindings.entries.removeIf { (entity, named) ->
            if (entity.level() !== level) false else {
                bindingCount -= named.size
                true
            }
        }
    }

    fun clear() {
        bindings.clear()
        bindingCount = 0
    }
}
