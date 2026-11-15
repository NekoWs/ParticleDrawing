package work.nekow.particledrawing.animation.script

import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphParticle
import work.nekow.particledrawing.animation.morph.MorphRuntime
import work.nekow.particledrawing.animation.morph.MorphSlot
import java.util.UUID

internal fun morphIdOf(index: Int): UUID = UUID(0x50444D4F525048L, index.toLong())

/** 按整刻推进限速、扰动与往返，渲染时只在两个已求值的整刻之间取样。 */
internal class ScriptMorph(
    private val startMs: Double,
    private val particles: List<MorphParticle>,
    private val slots: List<MorphSlot>,
    private val options: MorphOptions,
) {
    private val runtime = MorphRuntime(particles, slots, options)
    private val previous = particles.associate { it.id to it.position }.toMutableMap()
    private var step = 0L
    private var lastMs = startMs

    init {
        runtime.tick()
    }

    fun apply(timeMs: Double, hosts: List<ParticleHost>) {
        if (timeMs <= lastMs) return
        seekSteps(timeMs)
        val fraction = ((timeMs - startMs) / 50.0 - step).coerceIn(0.0, 1.0)
        for (h in hosts) {
            val id = morphIdOf(h.index)
            val from = previous[id] ?: continue
            val to = checkNotNull(runtime.positionOf(id))
            h.pos[0] = from.x + (to.x - from.x) * fraction
            h.pos[1] = from.y + (to.y - from.y) * fraction
            h.pos[2] = from.z + (to.z - from.z) * fraction
        }
        lastMs = timeMs
    }

    private fun seekSteps(timeMs: Double) {
        val target = ((timeMs - startMs) / 50.0).toLong()
        while (step < target) {
            if (runtime.isDone() && !options.keepVelocity) return
            for (particle in particles) previous[particle.id] = checkNotNull(runtime.positionOf(particle.id))
            runtime.tick()
            step++
        }
    }

    /** 循环起点快照很靠近形变开始，按同一个时钟重建可变运行时。 */
    fun copy(): ScriptMorph = ScriptMorph(startMs, particles, slots, options).also {
        it.seekSteps(lastMs)
        it.lastMs = lastMs
    }
}
