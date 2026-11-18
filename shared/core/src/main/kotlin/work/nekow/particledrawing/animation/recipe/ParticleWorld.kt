package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3

enum class RecipeCollision { NONE, SOLID, LIQUID, SOLID_AND_LIQUID, ENTITY, ALL }

data class ParticleSweep(
    val mode: RecipeCollision,
    val halfSize: Double = 0.0,
    val includeMaterial: Boolean = false,
)

enum class ParticleWorldField {
    BLOCK_LIGHT, SKY_LIGHT, OPEN_SKY, SOLID, IN_FLUID, FLUID_FLOW, NEAREST_ENTITY, FORCE_FIELD,
}

/** 同一世界坐标接口供积分、外观与公式使用。 */
fun interface ParticleWorld {
    fun cast(mode: RecipeCollision, from: Vec3, to: Vec3): SurfaceHit?

    fun cast(sweep: ParticleSweep, from: Vec3, to: Vec3): SurfaceHit? {
        requireLocalized(sweep.halfSize == 0.0) { translation("error.recipe.sweep_unavailable") }
        return cast(sweep.mode, from, to)
    }

    fun sample(position: Vec3, fields: Set<ParticleWorldField>): ParticleWorldSample = ParticleWorldSample()

    fun sample(position: Vec3, fields: Set<ParticleWorldField>, query: ParticleEntityQuery): ParticleWorldSample =
        sample(position, fields)
}

data class SurfaceHit(
    val position: Vec3,
    val normal: Vec3,
    val inside: Boolean = false,
    val material: ResourceId? = null,
    val center: Vec3 = position,
)

data class ParticleEntityQuery(val radius: Double = 8.0, val type: ResourceId? = null) {
    init {
        requireLocalized(radius in 0.0..32.0) { translation("error.recipe.entity_radius") }
    }
}

data class ParticleWorldSample(
    val loaded: Boolean = false,
    val blockLight: Int = 0,
    val skyLight: Int = 0,
    val openSky: Boolean = false,
    val solid: Boolean = false,
    val inFluid: Boolean = false,
    val fluidFlow: Vec3 = Vec3.ZERO,
    val nearestEntity: Vec3? = null,
    val fieldAcceleration: Vec3 = Vec3.ZERO,
)
