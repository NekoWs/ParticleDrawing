package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3

enum class RecipeCollision { NONE, SOLID, LIQUID, SOLID_AND_LIQUID }

enum class ParticleWorldField {
    BLOCK_LIGHT, SKY_LIGHT, OPEN_SKY, SOLID, IN_FLUID, FLUID_FLOW, NEAREST_ENTITY,
}

/** 同一世界坐标接口供积分、外观与公式使用。 */
fun interface ParticleWorld {
    fun cast(mode: RecipeCollision, from: Vec3, to: Vec3): SurfaceHit?

    fun cast(mode: RecipeCollision, from: Vec3, to: Vec3, includeMaterial: Boolean): SurfaceHit? = cast(mode, from, to)

    fun sample(position: Vec3, fields: Set<ParticleWorldField>): ParticleWorldSample = ParticleWorldSample()
}

data class SurfaceHit(
    val position: Vec3,
    val normal: Vec3,
    val inside: Boolean = false,
    val material: ResourceId? = null,
)

data class ParticleWorldSample(
    val loaded: Boolean = false,
    val blockLight: Int = 0,
    val skyLight: Int = 0,
    val openSky: Boolean = false,
    val solid: Boolean = false,
    val inFluid: Boolean = false,
    val fluidFlow: Vec3 = Vec3.ZERO,
    val nearestEntity: Vec3? = null,
)
