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
) {
    internal fun validate() {
        requireLocalized(event == ParticleEvent.CONTACT || (material == null && !removeParent)) {
            translation("error.recipe.event_contact")
        }
        requireLocalized(child.emissions.isEmpty() && child.subEmit == null && child.lifetime in 1..MAX_CHILD_LIFETIME &&
            child.seeds.size <= MAX_CHILD_SEEDS && !child.follow && child.orbit.isBlank()) {
            translation("error.recipe.event_child", MAX_CHILD_SEEDS, MAX_CHILD_LIFETIME)
        }
        child.validate()
    }

    companion object {
        const val MAX_RULES = 4
        const val MAX_CHILD_SEEDS = 64
        const val MAX_CHILD_LIFETIME = 1200
    }
}
