package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.HitResult
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.recipe.ParticleRecipeExecution
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.api.ParticleSourceHandle
import work.nekow.particledrawing.api.ParticleSources
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import java.util.UUID

@EventBusSubscriber(modid = ParticleDrawing.MODID)
internal object ServerProjectileEffects {
    private data class Binding(val level: ServerLevel, val trail: ParticleSourceHandle, val impact: ParticleRecipe)

    private val bindings = hashMapOf<UUID, Binding>()
    private val logger = LogManager.getLogger("ParticleDrawing")

    fun isBound(projectile: Projectile): Boolean = projectile.uuid in bindings

    fun bind(level: ServerLevel, projectile: Projectile, trail: ParticleRecipe, impact: ParticleRecipe) {
        requireLocalized(trail.lifetime > 0 && impact.lifetime > 0) { translation("error.projectile.lifetime") }
        val hitAnchor = RecipeAnchor(projectile.position())
        impact.validate()
        RecipeRuntime.requireCadence(impact, burst = true)
        ParticleRecipeExecution.validate(impact, hitAnchor, burst = true, mode = ParticleRecipeMode.STANDARD)
        val anchor = RecipeAnchor(projectile.position(), projectile.uuid, projectile.id)
        val handle = ParticleSources.start(level, trail, anchor)
        bindings[projectile.uuid] = Binding(level, handle, impact)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    @JvmStatic
    fun onImpact(event: ProjectileImpactEvent) {
        val projectile = event.projectile
        val level = projectile.level() as? ServerLevel ?: return
        val hit = event.rayTraceResult
        if (event.isCanceled || hit.type == HitResult.Type.MISS) return
        val binding = bindings[projectile.uuid]?.takeIf { it.level === level } ?: return
        bindings.remove(projectile.uuid)
        binding.trail.stop()
        try {
            ParticleSources.start(level, binding.impact, RecipeAnchor(hit.location), burst = true)
        } catch (error: IllegalArgumentException) {
            logger.debug("Could not start impact particles for {}: {}", projectile.uuid, error.message)
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onEntityLeave(event: EntityLeaveLevelEvent) {
        val level = event.level as? ServerLevel ?: return
        val binding = bindings[event.entity.uuid]?.takeIf { it.level === level } ?: return
        bindings.remove(event.entity.uuid)
        binding.trail.stop()
    }

    fun discard(level: ServerLevel) {
        bindings.values.removeIf { it.level === level }
    }

    fun clear() = bindings.clear()
}
