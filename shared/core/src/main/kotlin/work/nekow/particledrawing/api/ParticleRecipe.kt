package work.nekow.particledrawing.api

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import java.util.UUID
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 一颗出生样本；位置是锚点相对坐标，颜色可来自图片像素。 */
data class ParticleSeed(val position: Vec3, val color: Color = Color.WHITE, val u: Double = 0.0)

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
) {
    fun validate() {
        requireLocalized(seeds.isNotEmpty() && seeds.size <= MAX_SEEDS) { translation("error.recipe.seed_count", MAX_SEEDS) }
        requireLocalized(rate in 1..MAX_RATE && interval in 1..72000) { translation("error.recipe.cadence", MAX_RATE) }
        requireLocalized(lifetime == -1 || lifetime in 1..MAX_LIFETIME) { translation("error.recipe.lifetime", MAX_LIFETIME) }
        requireLocalized(maxAlive in 1..MAX_SEEDS) { translation("error.recipe.max_alive", MAX_SEEDS) }
        requireLocalized(listOf(variables, state, lets, birth, motion).all { it.size <= MAX_FIELDS }) { translation("error.recipe.fields_limit", MAX_FIELDS) }
        requireLocalized(orbit.length <= MAX_EXPRESSION) { translation("error.recipe.anchor_length", MAX_EXPRESSION) }
        for (s in seeds) {
            requireLocalized(listOf(s.position.x, s.position.y, s.position.z, s.u).all(Double::isFinite)) { translation("error.recipe.seed_finite") }
            requireLocalized(listOf(s.color.r, s.color.g, s.color.b, s.color.a).all { it.isFinite() && it in 0f..1f }) { translation("error.recipe.seed_color") }
        }
        RecipeExpressions(this)
    }

    companion object {
        const val MAX_SEEDS = 8192
        const val MAX_RATE = 1024
        const val MAX_LIFETIME = 72000
        const val MAX_FIELDS = 32
        const val MAX_EXPRESSION = 2048
    }
}
