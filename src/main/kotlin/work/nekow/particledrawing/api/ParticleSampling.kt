package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/** 只提供采样方式；图形与变换由出生公式组合。 */
object ParticleSampling {
    @JvmStatic
    fun count(count: Int): List<ParticleSeed> {
        require(count in 1..ParticleRecipe.MAX_SEEDS) { "样本数量需要 1..${ParticleRecipe.MAX_SEEDS}" }
        return List(count) { ParticleSeed(Vec3.ZERO, u = if (count == 1) 0.0 else it.toDouble() / (count - 1)) }
    }

    @JvmStatic
    fun range(begin: Double, end: Double, step: Double): List<ParticleSeed> {
        require(listOf(begin, end, step).all(Double::isFinite) && step > 0.0 && end >= begin) { "range 需要有限的 begin <= end，step > 0" }
        val count = floor((end - begin) / step + 1e-9) + 1.0
        require(count in 1.0..ParticleRecipe.MAX_SEEDS.toDouble()) { "range 样本过多，请增大步长" }
        return List(count.toInt()) { ParticleSeed(Vec3.ZERO, u = begin + it * step) }
    }

    @JvmStatic
    fun grid(min: Vec3, max: Vec3, step: Double): List<ParticleSeed> {
        require(step.isFinite() && step > 0.0) { "grid 步长必须为正" }
        fun count(a: Double, b: Double): Int {
            require(a.isFinite() && b.isFinite() && b >= a) { "grid 的最小坐标不能大于最大坐标" }
            val n = floor((b - a) / step + 1e-9) + 1.0
            require(n in 1.0..ParticleRecipe.MAX_SEEDS.toDouble()) { "grid 样本过多，请增大步长" }
            return n.toInt()
        }
        val nx = count(min.x, max.x); val ny = count(min.y, max.y); val nz = count(min.z, max.z)
        val total = nx.toLong() * ny * nz
        require(total <= ParticleRecipe.MAX_SEEDS) { "grid 有 $total 个样本，最多 ${ParticleRecipe.MAX_SEEDS}；请增大步长或缩小范围" }
        return List(total.toInt()) { i ->
            ParticleSeed(Vec3(min.x + (i % nx) * step, min.y + ((i / nx) % ny) * step, min.z + (i / (nx * ny)) * step),
                u = if (total == 1L) 0.0 else i.toDouble() / (total - 1))
        }
    }
}
