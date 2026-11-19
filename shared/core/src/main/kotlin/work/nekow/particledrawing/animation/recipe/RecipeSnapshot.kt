package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.RibbonHistory

internal data class RecipeLook(val visual: ParticleVisual, val curve: ParticleLifeCurve?)

internal data class RecipeDecal(val position: Vec3, val normal: Vec3, val radius: Double, val wrap: Boolean)

internal data class RecipeParticleSample(
    val id: Long, val look: RecipeLook, val position: Vec3, val color: Color, val scale: Float,
    val age: Int, val lifetime: Int, val glowing: Boolean, val light: Int, val decal: RecipeDecal?,
    val history: List<Vec3> = emptyList(),
)

internal data class RecipeSnapshot(val tick: Long, val particles: List<RecipeParticleSample>)

/** 保留仍在播放的主粒子、事件子粒子与刻级尾迹。快照可在本刻任意时机读取。 */
internal class SyncedRecipe(recipe: ParticleRecipe, burst: Boolean, world: ParticleWorld) {
    val runtime = RecipeRuntime(recipe, burst, world = world)
    private val children = RecipeChildren(world)
    private val orbit = DoubleArray(3)
    private val members = linkedMapOf<RecipeRuntime.Particle, Member>()
    private var nextId = 0L
    val finished get() = runtime.finished && children.active.isEmpty()

    private class Member(
        val id: Long, val look: RecipeLook, val decal: RecipeDecal?, val history: RibbonHistory?,
        var position: Vec3,
    )

    init { validate(recipe) }

    fun tick(anchor: RecipeFrame?) {
        runtime.tickInFrame(runtime.orbitFrame(anchor, orbit), MAX_PARENT)
        children.tick(runtime.events, MAX_CHILDREN, MAX_CHILDREN)
        updateMembers(runtime)
        for (child in children.active) updateMembers(child)
        children.prune()
    }

    fun edit(predicate: String, changes: Map<String, String>, remove: Boolean) {
        runtime.edit(predicate, changes, remove)
        for (dead in runtime.removedParticles) members.remove(dead)
    }

    private fun updateMembers(source: RecipeRuntime) {
        for (dead in source.removedParticles) members.remove(dead)
        val hidden = source.recipe.follow && source.frame == null
        val look = RecipeLook(source.recipe.visual, source.recipe.lifeCurve)
        for (particle in source.particles) {
            val member = members[particle]
            if (hidden && member == null) continue
            val position = if (hidden) checkNotNull(member).position else source.frameOf(particle).position(particle.x, particle.y, particle.z)
            if (member == null) {
                val event = particle.event
                val decal = event?.takeIf { it.decalRadius > 0.0 }?.let {
                    RecipeDecal(it.position, it.normal, it.decalRadius, it.wrapDecal)
                }
                val history = look.visual.trailTicks.takeIf { it > 0 }?.let { RibbonHistory(it, position) }
                members[particle] = Member(nextId++, look, decal, history, position)
            } else {
                member.position = position
                member.history?.record(position)
            }
        }
    }

    fun snapshot(includeHistory: Boolean): RecipeSnapshot {
        val hidden = runtime.recipe.follow && runtime.frame == null
        val samples = ArrayList<RecipeParticleSample>(members.size)
        for ((particle, member) in members) {
            if (!particle.alive) continue
            val r = particle.regs
            val alpha = if (hidden && particle.event == null) 0f else r[Reg.A].toFloat()
            samples += RecipeParticleSample(
                member.id, member.look, member.position, Color.of(r[Reg.R].toFloat(), r[Reg.G].toFloat(), r[Reg.B].toFloat(), alpha),
                r[Reg.SC].toFloat(), particle.age, particle.lifetime, r[Reg.GLOW] != 0.0, r[Reg.LIGHT].toInt(), member.decal,
                if (includeHistory) member.history?.oldestFirst().orEmpty() else emptyList(),
            )
        }
        return RecipeSnapshot(runtime.ticks, samples)
    }

    /** 服务端投递首帧时按需读取。历史仍归当前模拟线程所有。 */
    fun activeTrails(): Map<Long, RibbonHistory> = buildMap {
        for ((particle, member) in members) {
            if (particle.alive && member.history != null) put(member.id, member.history)
        }
    }

    companion object {
        const val MAX_PARENT = 64
        const val MAX_CHILDREN = 64
        const val MAX_PARTICLES = MAX_PARENT + MAX_CHILDREN

        fun validate(recipe: ParticleRecipe) {
            requireLocalized(recipe.maxAlive <= MAX_PARENT && recipe.rate <= MAX_PARENT) {
                translation("error.recipe.synced_limit", MAX_PARENT)
            }
            requireLocalized(recipe.subEmit == null) { translation("error.recipe.synced_subemit") }
            requireLocalized(recipe.simulationRange == 0.0) { translation("error.recipe.synced_range") }
        }
    }
}
