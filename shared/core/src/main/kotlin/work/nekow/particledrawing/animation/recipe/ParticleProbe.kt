package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3

/**
 * 一次世界探测的声明：从哪里、朝哪个方向、探多远、只认什么。
 *
 * 探测本身不改变粒子状态，只把命中结果写进公式输入。多组探测按声明顺序编号，
 * 第 0 组沿用不带序号的输入名（`probe_hit` 等），第 1 组起是 `probe1_hit`。
 *
 * 起点是相对**粒子当前位置**的偏移；方向留空表示沿当前速度。扫掠半宽 > 0 时按盒体扫掠，
 * 避免高速或体积大的粒子漏检。
 */
data class ParticleProbe(
    val range: Double,
    val mode: RecipeCollision = RecipeCollision.SOLID,
    /** 固定方向；空 = 沿当前速度。 */
    val direction: Vec3? = null,
    val material: ResourceId? = null,
    /** 相对粒子位置的探测起点偏移。 */
    val origin: Vec3 = Vec3.ZERO,
    /** > 0 时按该半宽的盒体扫掠，而不是射线。 */
    val halfSize: Double = 0.0,
) {
    private val fixed = direction?.normalize()
    private val sweep = ParticleSweep(mode, halfSize, includeMaterial = material != null)

    init {
        requireLocalized(range in MIN_RANGE..MAX_RANGE) { translation("error.recipe.probe_range", MIN_RANGE, MAX_RANGE) }
        requireLocalized(mode != RecipeCollision.NONE) { translation("error.recipe.probe_mode") }
        requireLocalized(direction == null || (direction.lengthSqr().isFinite() && fixed != Vec3.ZERO)) {
            translation("error.recipe.probe_direction")
        }
        requireLocalized(origin.lengthSqr().isFinite()) { translation("error.recipe.probe_direction") }
        requireLocalized(halfSize in 0.0..MAX_HALF_SIZE) { translation("error.recipe.probe_range", 0.0, MAX_HALF_SIZE) }
    }

    internal data class Reading(
        val distance: Double,
        val hit: SurfaceHit? = null,
        val matches: Boolean = false,
    )

    internal fun read(world: ParticleWorld?, frame: RecipeFrame?, position: Vec3, velocity: Vec3): Reading {
        if (world == null || frame == null) return empty
        val axis = fixed ?: velocity.normalize()
        if (axis == Vec3.ZERO) return empty
        val from = position.add(origin)
        val end = from.add(frame.velocity(axis).scale(range))
        val hit = world.cast(sweep, from, end) ?: return empty
        return Reading(from.distanceTo(hit.position).coerceAtMost(range), hit, material == null || material == hit.material)
    }

    private val empty = Reading(range)

    companion object {
        const val MIN_RANGE = 0.01
        const val MAX_RANGE = 1024.0
        const val MAX_HALF_SIZE = 256.0
    }
}