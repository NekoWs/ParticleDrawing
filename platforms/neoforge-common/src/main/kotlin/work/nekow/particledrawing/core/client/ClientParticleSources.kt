package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.RecipeChildren
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.api.SubEmit
import work.nekow.particledrawing.core.network.ParticleRecipePayload
import work.nekow.particledrawing.core.network.RecipeOperation
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.localizedError
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import work.nekow.particledrawing.platform.PlatformCompat
import work.nekow.particledrawing.platform.toCore
import java.util.UUID

/** 声明式采样器的客户端桥接；所有积分按游戏刻推进，暂停时不推进。 */
internal object ClientParticleSources {
    private enum class Suspension { ACTIVE, RESET, PRESERVED }

    private class Member(
        val binding: ClientParticleEngine.RecipeBinding, var position: Vec3, var color: Color, var scale: Float,
    ) {
        val id: UUID get() = binding.particle.id()
    }
    private class Entry(val id: UUID, var runtime: RecipeRuntime, var anchor: RecipeAnchor, val instanced: Boolean) {
        val members = linkedMapOf<RecipeRuntime.Particle, Member>()
        val strandMembers = hashMapOf<Long, BridgeParticle>()
        val children = RecipeChildren(ClientParticleWorld)
        val childViews = linkedMapOf<RecipeRuntime, Entry>()
        var suspension = Suspension.ACTIVE

        fun particleIds(): List<UUID> = members.values.map { it.id } + childViews.values.flatMap(Entry::particleIds)
    }
    private val sources = linkedMapOf<UUID, Entry>()

    fun handle(p: ParticleRecipePayload) {
        try {
            when (p.operation) {
                RecipeOperation.START -> {
                    remove(p.id)
                    ClientGpuParticles.remove(p.id)
                    val recipe = requireNotNull(p.recipe)
                    recipe.validate()
                    val anchor = requireNotNull(p.anchor)
                    // 调试旧管线时以 CPU 启动 GPU 配方。CPU 实例在渲染阶段按帧选择管线。
                    val legacy = ShaderCompat.legacyPipelines()
                    if (p.mode == ParticleRecipeMode.GPU && !legacy) ClientGpuParticles.start(p.id, recipe, anchor)
                    else sources[p.id] = Entry(p.id, RecipeRuntime(recipe, p.burst, world = ClientParticleWorld), anchor,
                        p.mode == ParticleRecipeMode.INSTANCED)
                }
                RecipeOperation.UPDATE -> sources[p.id]?.runtime?.update(requireNotNull(p.recipe))
                RecipeOperation.ANCHOR -> sources[p.id]?.anchor = requireNotNull(p.anchor)
                RecipeOperation.VARIABLE -> sources[p.id]?.runtime?.let { it.update(it.recipe.copy(variables = it.recipe.variables + p.variables)) }
                RecipeOperation.STOP -> {
                    ClientGpuParticles.stop(p.id)
                    sources[p.id]?.let {
                        if (it.suspension != Suspension.ACTIVE) remove(p.id) else it.runtime.emitting = false
                    }
                }
                RecipeOperation.REMOVE -> {
                    remove(p.id)
                    ClientSyncedRecipes.remove(p.id)
                    ClientGpuParticles.remove(p.id)
                }
                RecipeOperation.EDIT -> sources[p.id]?.let {
                    ParticleInstanceDraws.invalidate()
                    it.runtime.edit(p.predicate, p.changes, p.deleteSelected)
                    if (it.suspension == Suspension.PRESERVED) {
                        ClientParticleEngine.instance()?.let { engine -> removeDead(engine, it, keepTrails = false) }
                    } else sync(it, emitChildren = false)
                }
            }
        } catch (e: RuntimeException) { remove(p.id); error(p.id, e) }
    }

    fun tick() {
        var childBudget = CHILD_ATTEMPTS_PER_TICK
        var camera: CoreVec3? = null
        for ((id, e) in sources.toList()) {
            try {
                val anchor = resolveRecipeAnchor(e.anchor)
                val range = e.runtime.recipe.simulationRange
                if (range > 0.0) {
                    val observer = camera ?: PlatformCompat.cameraPosition(Minecraft.getInstance()).toCore().also { camera = it }
                    if (RecipeRuntime.outsideSimulationRange(range, anchor?.origin, observer, e.suspension != Suspension.ACTIVE)) {
                        if (!e.runtime.emitting) remove(id)
                        else if (e.runtime.recipe.preserveSimulation) {
                            if (e.suspension == Suspension.ACTIVE) {
                                setPresentationSuspended(e, true)
                                e.suspension = Suspension.PRESERVED
                            }
                        } else if (e.suspension != Suspension.RESET) resetOutsideRange(e)
                        continue
                    }
                }
                if (e.suspension == Suspension.PRESERVED) {
                    e.runtime.resumeSimulation()
                    setPresentationSuspended(e, false)
                }
                e.suspension = Suspension.ACTIVE
                e.runtime.tickInFrame(e.runtime.orbitFrame(anchor, orbitOut))
                sync(e)
                childBudget -= e.children.tick(e.runtime.events, childBudget)
                for (child in e.children.active) sync(e.childViews.getOrPut(child) { Entry(e.id, child, e.anchor, e.instanced) }, allowCapacityDrop = true)
                e.children.prune()
                e.childViews.keys.retainAll(e.children.active)
                if (e.runtime.finished && e.children.active.isEmpty()) sources.remove(id)
            } catch (ex: RuntimeException) { remove(id); error(id, ex) }
        }
    }

    private val posOut = DoubleArray(3)

    private fun sync(e: Entry, emitChildren: Boolean = true, allowCapacityDrop: Boolean = false) {
        val engine = ClientParticleEngine.instance() ?: return
        val frame = e.runtime.frame
        val hidden = e.runtime.recipe.follow && frame == null
        val subEmit = e.runtime.recipe.subEmit
        val dead = removeDead(engine, e, keepTrails = emitChildren)

        val slots = e.runtime.slots
        val sR = slots.slot("r"); val sG = slots.slot("g"); val sB = slots.slot("b"); val sA = slots.slot("a")
        val sSc = slots.slot("sc"); val sGlow = slots.slot("glow"); val sLight = slots.slot("light")
        for (p in e.runtime.particles) {
            var member = e.members[p]
            if (member == null && frame == null) continue
            val position = if (hidden && member != null) member.position else {
                e.runtime.frameOf(p).positionInto(p.x, p.y, p.z, posOut)
                val previous = member?.position
                if (previous != null && previous.x == posOut[0] && previous.y == posOut[1] && previous.z == posOut[2]) previous
                else Vec3(posOut[0], posOut[1], posOut[2])
            }
            val r = p.regs[sR].toFloat(); val g = p.regs[sG].toFloat(); val b = p.regs[sB].toFloat()
            val alpha = if (hidden) 0f else p.regs[sA].toFloat()
            val scale = p.regs[sSc].toFloat()
            val glow = p.regs[sGlow] != 0.0
            val light = p.regs[sLight].toInt()
            val previousColor = member?.color
            val color = if (previousColor != null && previousColor.r == r && previousColor.g == g &&
                previousColor.b == b && previousColor.a == alpha) previousColor else Color.of(r, g, b, alpha)
            if (member == null) {
                val id = UUID.randomUUID()
                val look = ResolvedVisual.of(e.runtime.recipe.visual, 1f)
                val event = p.event
                val decal = if (event != null && event.decalRadius > 0.0) {
                    ParticleModelRenderer.decal(position.toCore(), event.position, event.normal, event.decalRadius, event.wrapDecal)
                } else null
                if (event != null && event.decalRadius > 0.0 && decal == null) {
                    p.alive = false
                    continue
                }
                engine.spawnParticle(id, position.x, position.y, position.z, r, g, b, alpha,
                    scale, p.lifetime, null, glow, light, look, e.runtime.recipe.lifeCurve, decal = decal)
                val binding = engine.bindRecipeParticle(id, look)
                if (binding == null) {
                    if (allowCapacityDrop) {
                        p.alive = false
                        continue
                    }
                    localizedError(translation("error.recipe.client_limit"))
                }
                binding.bridge?.let {
                    it.instanced = e.instanced
                    if (look.trailStyle.lingerTicks > 0) it.trailOwner = e.id
                }
                member = Member(binding, position, color, scale)
                e.members[p] = member
            }
            member.position = position
            member.color = color
            member.scale = scale
            engine.applyRecipeFrame(member.binding, position, color, scale, glow, light)
        }

        linkStrands(e)

        // 优先补主发射器的成员，余下的容量再给子粒子。
        if (emitChildren && subEmit != null) for ((particle, member) in dead) {
            burst(engine, e, particle, member, subEmit)
        }
    }

    private fun linkStrands(entry: Entry) {
        val length = entry.runtime.recipe.strandSamples
        val byIndex = entry.strandMembers
        if (length == 0 && byIndex.isEmpty()) return
        byIndex.clear()
        for ((particle, member) in entry.members) {
            val bridge = member.binding.bridge ?: continue
            if (length == 0) bridge.recipeStrand = null else byIndex[particle.index] = bridge
        }
        if (length == 0) return
        for ((index, bridge) in byIndex) {
            val link = bridge.recipeStrand ?: RecipeStrand().also { bridge.recipeStrand = it }
            val local = index % length
            link.previous = if (local > 0) byIndex[index - 1] else null
            link.next = if (local < length - 1) byIndex[index + 1] else null
        }
    }

    private val orbitOut = DoubleArray(3)

    private fun removeDead(engine: ClientParticleEngine, entry: Entry,
                           keepTrails: Boolean): List<Pair<RecipeRuntime.Particle, Member>> {
        if (entry.runtime.removedParticles.isEmpty()) return emptyList()
        val dead = entry.runtime.removedParticles.mapNotNull { particle ->
            entry.members.remove(particle)?.let { particle to it }
        }
        if (dead.isNotEmpty()) {
            val ids = dead.map { (_, member) -> member.id }.toTypedArray()
            if (keepTrails) engine.finishParticles(ids) else engine.destroyParticles(ids)
        }
        return dead
    }

    private fun setPresentationSuspended(entry: Entry, suspend: Boolean) {
        val engine = ClientParticleEngine.instance() ?: return
        engine.suspendDetachedTrails(entry.id, suspend)
        for (member in entry.members.values) engine.setRecipeSuspended(member.binding, suspend)
        for (child in entry.childViews.values) for (member in child.members.values) {
            engine.setRecipeSuspended(member.binding, suspend)
        }
    }

    private fun resetOutsideRange(e: Entry) {
        ClientParticleEngine.instance()?.let {
            it.destroyParticles(e.particleIds().toTypedArray())
            it.clearDetachedTrails(e.id)
        }
        e.members.clear()
        e.strandMembers.clear()
        e.children.clear()
        e.childViews.clear()
        e.runtime = RecipeRuntime(e.runtime.recipe, burst = false, world = ClientParticleWorld)
        e.suspension = Suspension.RESET
    }

    internal fun error(id: UUID, e: RuntimeException) {
        org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").error("{} {}", translation("log.recipe.instance").resolve(), id, e)
    }

    private fun remove(id: UUID) {
        val e = sources.remove(id)
        ClientParticleEngine.instance()?.let {
            if (e != null) it.destroyParticles(e.particleIds().toTypedArray())
            it.clearDetachedTrails(id)
        }
    }

    fun clear() = sources.clear()

    private const val CHILD_ATTEMPTS_PER_TICK = 4096

    private fun burst(engine: ClientParticleEngine, e: Entry, p: RecipeRuntime.Particle, member: Member, sub: SubEmit) {
        val pos = member.position.toCore()
        val scale = (member.scale * sub.scale).toFloat()
        sub.forEachVelocity(p.index, e.runtime.ticks) { velocity ->
            engine.spawnLocalEmitterParticle(
                pos, velocity, member.color.r, member.color.g, member.color.b, 1f,
                scale, sub.lifetime, false, 0, member.binding.visual,
            )
        }
    }
}

