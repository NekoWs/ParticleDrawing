package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.math.Vec3
import kotlin.math.floor

/** 同一配方内读取本刻推进前的邻居快照。 */
internal class ParticleNeighbors(private val radius: Double) {
    private data class Cell(val x: Long, val y: Long, val z: Long)
    private data class Member(val particle: RecipeRuntime.Particle, val position: Vec3, val velocity: Vec3)
    data class Sample(val count: Int, val offset: Vec3, val velocity: Vec3)

    private val cells = hashMapOf<Cell, MutableList<Member>>()
    private val radiusSquared = radius * radius

    fun clear() = cells.clear()

    fun add(particle: RecipeRuntime.Particle, position: Vec3, velocity: Vec3) {
        cells.getOrPut(cellOf(position)) { arrayListOf() }.add(Member(particle, position, velocity))
    }

    fun at(particle: RecipeRuntime.Particle, position: Vec3): Sample {
        val cell = cellOf(position)
        var count = 0
        var px = 0.0
        var py = 0.0
        var pz = 0.0
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
        for (y in -1L..1L) for (z in -1L..1L) for (x in -1L..1L) {
            val nearby = cells[Cell(cell.x + x, cell.y + y, cell.z + z)] ?: continue
            // 按出生顺序分布取样，让密集粒子团的开销有上界。
            val stride = (nearby.size + CELL_BUDGET - 1) / CELL_BUDGET
            for (index in nearby.indices step stride) {
                val candidate = nearby[index]
                if (candidate.particle === particle || position.distanceToSqr(candidate.position) > radiusSquared) continue
                count++
                px += candidate.position.x - position.x
                py += candidate.position.y - position.y
                pz += candidate.position.z - position.z
                vx += candidate.velocity.x
                vy += candidate.velocity.y
                vz += candidate.velocity.z
            }
        }
        if (count == 0) return EMPTY
        val inv = 1.0 / count
        return Sample(count, Vec3(px * inv, py * inv, pz * inv), Vec3(vx * inv, vy * inv, vz * inv))
    }

    private fun cellOf(p: Vec3) = Cell(
        floor(p.x / radius).toLong(), floor(p.y / radius).toLong(), floor(p.z / radius).toLong(),
    )

    companion object {
        private const val CELL_BUDGET = 24
        val EMPTY = Sample(0, Vec3.ZERO, Vec3.ZERO)
    }
}
