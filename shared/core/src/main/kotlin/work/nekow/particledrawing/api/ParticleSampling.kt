package work.nekow.particledrawing.api

import work.nekow.particledrawing.math.Vec3
import kotlin.math.floor
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 只提供采样方式；图形与变换由出生公式组合。 */
object ParticleSampling {
    @JvmStatic
    fun count(count: Int): List<ParticleSeed> {
        requireLocalized(count in 1..ParticleRecipe.MAX_SEEDS) { translation("error.sampling.count", ParticleRecipe.MAX_SEEDS) }
        return List(count) { ParticleSeed(Vec3.ZERO, u = if (count == 1) 0.0 else it.toDouble() / (count - 1)) }
    }

    @JvmStatic
    fun range(begin: Double, end: Double, step: Double): List<ParticleSeed> {
        requireLocalized(listOf(begin, end, step).all(Double::isFinite) && step > 0.0 && end >= begin) { translation("error.sampling.range") }
        val count = floor((end - begin) / step + 1e-9) + 1.0
        requireLocalized(count in 1.0..ParticleRecipe.MAX_SEEDS.toDouble()) { translation("error.sampling.step_limit") }
        return List(count.toInt()) { ParticleSeed(Vec3.ZERO, u = begin + it * step) }
    }

    @JvmStatic
    fun grid(min: Vec3, max: Vec3, step: Double): List<ParticleSeed> {
        requireLocalized(step.isFinite() && step > 0.0) { translation("error.sampling.grid_step") }
        fun count(a: Double, b: Double): Int {
            requireLocalized(a.isFinite() && b.isFinite() && b >= a) { translation("error.sampling.grid_bounds") }
            val n = floor((b - a) / step + 1e-9) + 1.0
            requireLocalized(n in 1.0..ParticleRecipe.MAX_SEEDS.toDouble()) { translation("error.sampling.step_limit") }
            return n.toInt()
        }
        val nx = count(min.x, max.x); val ny = count(min.y, max.y); val nz = count(min.z, max.z)
        val total = nx.toLong() * ny * nz
        requireLocalized(total <= ParticleRecipe.MAX_SEEDS) { translation("error.sampling.grid_limit", total, ParticleRecipe.MAX_SEEDS) }
        return List(total.toInt()) { i ->
            ParticleSeed(Vec3(min.x + (i % nx) * step, min.y + ((i / nx) % ny) * step, min.z + (i / (nx * ny)) * step),
                u = if (total == 1L) 0.0 else i.toDouble() / (total - 1))
        }
    }
}
