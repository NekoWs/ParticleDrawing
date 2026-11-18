package work.nekow.particledrawing.api

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.animation.recipe.ParticleEntityQuery
import work.nekow.particledrawing.animation.recipe.ParticleProbe
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import java.util.UUID
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 一颗出生样本；位置是锚点相对坐标，颜色可来自图片像素。 */
data class ParticleSeed(
    val position: Vec3,
    val color: Color = Color.WHITE,
    val u: Double = 0.0,
    val normal: Vec3? = null,
)

/**
 * 子发射：粒子正常死亡时在原地炸出一批短寿命粒子。
 *
 * 只做一层，子粒子不会再触发子发射——递归会让数量指数爆炸，也很难在客户端做预算。
 * 子粒子沿用父配方外观，只是更小、更短命，用来表现破碎或消散。
 */
data class SubEmit(
    /** 每次爆发几颗。 */
    val count: Int = 8,
    /** 子粒子寿命（刻）。 */
    val lifetime: Int = 12,
    /** 初速（格/刻）；方向在球面上均匀分布。 */
    val speed: Double = 0.12,
    /** 尺寸相对父粒子的比例。 */
    val scale: Double = 0.6,
) {
    fun validate() {
        requireLocalized(count in 1..MAX_COUNT) { translation("error.recipe.subemit_count", MAX_COUNT) }
        requireLocalized(lifetime in 1..ParticleRecipe.MAX_LIFETIME) { translation("error.recipe.subemit_lifetime", ParticleRecipe.MAX_LIFETIME) }
        requireLocalized(speed in 0.0..MAX_SPEED) { translation("error.recipe.subemit_speed", MAX_SPEED) }
        requireLocalized(scale in 0.05..4.0) { translation("error.recipe.subemit_scale") }
    }

    /** 每次爆发使用稳定的种子，方向在球面上均匀分布。 */
    internal inline fun forEachVelocity(index: Long, tick: Long, action: (Vec3) -> Boolean) {
        val next = work.nekow.particledrawing.animation.script.mulberry32((31L * index + tick).toInt())
        repeat(count) {
            val y = next() * 2.0 - 1.0
            val angle = next() * Math.PI * 2.0
            val ring = kotlin.math.sqrt(1.0 - y * y)
            val velocity = Vec3(ring * kotlin.math.cos(angle), y, ring * kotlin.math.sin(angle)).scale(speed)
            if (!action(velocity)) return
        }
    }

    companion object {
        const val MAX_COUNT = 64
        const val MAX_SPEED = 2.0
    }
}

/** 命令绘制的实体锚点以 UUID 为身份，网络 id 只用于快速查找。 */
data class RecipeAnchor(
    val position: Vec3,
    val entity: UUID? = null,
    val entityId: Int = -1,
    val offset: Vec3 = Vec3.ZERO,
    val local: Boolean = false,
)

/**
 * 可组合的粒子生成声明。出生样本、出生公式和逐刻行为分开配置；运行期只同步声明的改动。
 * 时间单位：t 为发射器运行毫秒，age/life 为粒子刻数，dt 固定为 1 刻，速度单位为格/刻。
 */
data class ParticleRecipe(
    val seeds: List<ParticleSeed>,
    val rate: Int = 8,
    val interval: Int = 1,
    val lifetime: Int = 80,
    val maxAlive: Int = 4096,
    val follow: Boolean = false,
    val variables: Map<String, Double> = emptyMap(),
    val state: Map<String, Double> = emptyMap(),
    val lets: Map<String, String> = emptyMap(),
    val birth: Map<String, String> = emptyMap(),
    val motion: Map<String, String> = emptyMap(),
    val where: String = "true",
    val kill: String = "false",
    val visual: ParticleVisual = ParticleVisual(),
    val lifeCurve: ParticleLifeCurve? = null,
    /**
     * 锚点偏移公式（vec3），按发射器运行毫秒 `t` 求值，叠加在锚点原点之上。
     * 空串表示不偏移。只允许引用 `t`、variable、state 与 let。
     */
    val orbit: String = "",
    /** 与世界的碰撞方式；NONE 时粒子按速度自由穿过。 */
    val collision: RecipeCollision = RecipeCollision.NONE,
    /** 撞到后法线方向保留的速度比例，0 = 直接停下，1 = 完全弹性。 */
    val bounce: Double = 0.35,
    /** 撞到后切向速度的保留比例，越小越「黏地」。 */
    val friction: Double = 0.55,
    /** 子发射；null = 死亡后什么都不留。 */
    val subEmit: SubEmit? = null,
    val emissions: List<RecipeEmission> = emptyList(),
    /** 每走多少格执行一次发射；0 时按 [interval] 刻发射。 */
    val spacing: Double = 0.0,
    /** 粒子碰撞盒的世界格半宽。0 使用中心点。 */
    val collisionHalfSize: Double = 0.0,
    val collisionSteps: Int = 1,
    val entityQuery: ParticleEntityQuery = ParticleEntityQuery(),
    val neighborRadius: Double = 2.0,
    val probe: ParticleProbe? = null,
    /** 持续配方离镜头超过该距离时回收全部粒子，回到范围时重新发射。0 表示始终模拟。 */
    val simulationRange: Double = 0.0,
) {
    fun validate() {
        requireLocalized(seeds.isNotEmpty() && seeds.size <= MAX_SEEDS) { translation("error.recipe.seed_count", MAX_SEEDS) }
        requireLocalized(rate in 1..MAX_RATE && interval in 1..72000) { translation("error.recipe.cadence", MAX_RATE) }
        requireLocalized(spacing == 0.0 || spacing in MIN_SPACING..MAX_SPACING) {
            translation("error.recipe.spacing", MIN_SPACING, MAX_SPACING)
        }
        requireLocalized(lifetime == -1 || lifetime in 1..MAX_LIFETIME) { translation("error.recipe.lifetime", MAX_LIFETIME) }
        requireLocalized(maxAlive in 1..MAX_SEEDS) { translation("error.recipe.max_alive", MAX_SEEDS) }
        requireLocalized(listOf(variables, state, lets, birth, motion).all { it.size <= MAX_FIELDS }) { translation("error.recipe.fields_limit", MAX_FIELDS) }
        requireLocalized(orbit.length <= MAX_EXPRESSION) { translation("error.recipe.anchor_length", MAX_EXPRESSION) }
        requireLocalized(collisionHalfSize in 0.0..MAX_COLLISION_HALF_SIZE) {
            translation("error.recipe.sweep_size", MAX_COLLISION_HALF_SIZE)
        }
        requireLocalized(collisionSteps in 1..MAX_COLLISION_STEPS) { translation("error.recipe.collision_iterations", MAX_COLLISION_STEPS) }
        requireLocalized(neighborRadius in 0.05..16.0) { translation("error.recipe.neighbor_radius") }
        requireLocalized(simulationRange in 0.0..MAX_SIMULATION_RANGE) {
            translation("error.recipe.simulation_range", MAX_SIMULATION_RANGE)
        }
        requireLocalized(bounce in 0.0..1.0) { translation("error.recipe.bounce") }
        requireLocalized(friction in 0.0..1.0) { translation("error.recipe.friction") }
        for (s in seeds) {
            requireLocalized(listOf(s.position.x, s.position.y, s.position.z, s.u).all(Double::isFinite)) { translation("error.recipe.seed_finite") }
            s.normal?.let { normal ->
                requireLocalized(listOf(normal.x, normal.y, normal.z).all(Double::isFinite)) { translation("error.mesh.finite") }
            }
            requireLocalized(listOf(s.color.r, s.color.g, s.color.b, s.color.a).all { it.isFinite() && it in 0f..1f }) { translation("error.recipe.seed_color") }
        }
        subEmit?.validate()
        requireLocalized(emissions.size <= RecipeEmission.MAX_RULES) { translation("error.recipe.event_limit", RecipeEmission.MAX_RULES) }
        emissions.forEach(RecipeEmission::validate)
        RecipeExpressions(this)
    }

    companion object {
        const val MAX_SEEDS = 8192
        const val MAX_RATE = 1024
        const val MIN_SPACING = 0.05
        const val MAX_SPACING = 64.0
        const val MAX_COLLISION_HALF_SIZE = 1.0
        const val MAX_COLLISION_STEPS = 4
        const val MAX_SIMULATION_RANGE = 512.0
        const val MAX_LIFETIME = 72000
        const val MAX_FIELDS = 32
        const val MAX_EXPRESSION = 2048
    }
}
