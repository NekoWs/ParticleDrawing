package work.nekow.particledrawing.api

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId

enum class ParticleEvent { BIRTH, CONTACT, DEATH }

/** 在事件位置执行目标配方的一轮采样。 */
data class RecipeEmission(
    val event: ParticleEvent,
    val child: ParticleRecipe,
    val material: ResourceId? = null,
    val once: Boolean = true,
    val removeParent: Boolean = false,
    val decalRadius: Double = 0.0,
    val wrapDecal: Boolean = false,
) {
    internal fun validate() {
        requireLocalized(decalRadius in 0.0..MAX_DECAL_RADIUS && (decalRadius == 0.0 || event == ParticleEvent.CONTACT) &&
            (!wrapDecal || decalRadius > 0.0)) {
            translation("error.recipe.event_decal", MAX_DECAL_RADIUS)
        }
        requireLocalized(event == ParticleEvent.CONTACT || (material == null && !removeParent)) {
            translation("error.recipe.event_contact")
        }
        requireLocalized(child.emissions.isEmpty() && child.subEmit == null && child.lifetime in 1..MAX_CHILD_LIFETIME &&
            child.seeds.size <= MAX_CHILD_SEEDS && child.spacing == 0.0 && !child.follow && child.orbit.isBlank()) {
            translation("error.recipe.event_child", MAX_CHILD_SEEDS, MAX_CHILD_LIFETIME)
        }
        requireLocalized(child.simulationRange == 0.0) { translation("error.recipe.simulation_burst") }
        requireLocalized(child.strandSamples == 0) { translation("error.recipe.strand_mode") }
        child.validate()
    }

    companion object {
        /** 事件规则数与子配方样本数一起受单个自定义包 1 MiB 限制，不能只按性能放开。 */
        const val MAX_RULES = 16
        const val MAX_DECAL_RADIUS = 64.0
        const val MAX_CHILD_SEEDS = 128
        const val MAX_CHILD_LIFETIME = ParticleRecipe.MAX_LIFETIME
    }
}
