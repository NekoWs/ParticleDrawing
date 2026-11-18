package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.diagnostic
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.platform.toMinecraft


import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.SubEmit
import work.nekow.particledrawing.api.VelocityStretch
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.core.network.ParticleRecipePayload
import work.nekow.particledrawing.core.network.RecipeOperation
import work.nekow.particledrawing.util.AttachMath
import java.util.UUID
import work.nekow.particledrawing.i18n.translation

/** 声明式采样器的客户端桥接；所有积分按游戏刻推进，暂停时不推进。 */
internal object ClientParticleSources {
    private data class Frame(val pos: Vec3, val yaw: Float = 0f, val pitch: Float = 0f, val local: Boolean = false) {
        fun apply(v: Vec3) = AttachMath.resolve(pos, yaw, pitch, v, local)

        /** 原地版本：结果写进 [out] 的前三项，逐粒子同步不建中间对象。 */
        fun applyInto(x: Double, y: Double, z: Double, out: DoubleArray) =
            AttachMath.resolveInto(pos.x, pos.y, pos.z, yaw, pitch, x, y, z, local, out)
    }
    private class Member(val id: UUID, val frame: Frame, val visual: ResolvedVisual)
    private class Entry(val runtime: RecipeRuntime, var anchor: RecipeAnchor) {
        val members = linkedMapOf<RecipeRuntime.Particle, Member>()
    }
    private val sources = linkedMapOf<UUID, Entry>()

    fun handle(p: ParticleRecipePayload) {
        try {
            when (p.operation) {
                RecipeOperation.START -> {
                    remove(p.id)
                    val recipe = requireNotNull(p.recipe)
                    recipe.validate()
                    sources[p.id] = Entry(RecipeRuntime(recipe, p.burst, world = ClientParticleWorld), requireNotNull(p.anchor))
                }
                RecipeOperation.UPDATE -> sources[p.id]?.runtime?.update(requireNotNull(p.recipe))
                RecipeOperation.ANCHOR -> sources[p.id]?.anchor = requireNotNull(p.anchor)
                RecipeOperation.VARIABLE -> sources[p.id]?.runtime?.let { it.update(it.recipe.copy(variables = it.recipe.variables + p.variables)) }
                RecipeOperation.STOP -> sources[p.id]?.runtime?.emitting = false
                RecipeOperation.REMOVE -> remove(p.id)
                RecipeOperation.EDIT -> sources[p.id]?.let { it.runtime.edit(p.predicate, p.changes, p.deleteSelected); sync(it, resolve(it)) }
            }
        } catch (e: RuntimeException) { remove(p.id); error(p.id, e) }
    }

    fun tick() {
        for ((id, e) in sources.toList()) {
            try {
                val frame = resolve(e)
                e.runtime.tick(frame != null)
                sync(e, frame)
                if (e.runtime.finished) sources.remove(id)
            } catch (ex: RuntimeException) { remove(id); error(id, ex) }
        }
    }

    // 逐 tick 复用：别为每颗粒子攒一份哈希集/数组，几千颗 ×20tps 就是几十万次分配
    private val livingSet: MutableSet<RecipeRuntime.Particle> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val deadIds = ArrayList<UUID>()

    /** 逐粒子同步复用的落点，避免每颗每刻建 Vec3。 */
    private val posOut = DoubleArray(3)

    private fun sync(e: Entry, frame: Frame?) {
        val engine = ClientParticleEngine.instance() ?: return
        // 反向清理：members 里 runtime 已不再持有的粒子
        livingSet.clear()
        for (p in e.runtime.particles) livingSet.add(p)
        // 子发射：粒子正常消失时在原地炸一批更小更短命的粒子。
        // 子粒子是普通渲染粒子，不会再触发子发射，所以不存在递归放大。
        val subEmit = e.runtime.recipe.subEmit
        val dead = e.members.entries.iterator()
        while (dead.hasNext()) {
            val entry = dead.next()
            if (entry.key !in livingSet) {
                if (subEmit != null) burst(e, entry.key, entry.value, subEmit)
                deadIds.add(entry.value.id)
                dead.remove()
            }
        }
        if (deadIds.isNotEmpty()) {
            engine.destroyParticles(deadIds.toTypedArray())
            deadIds.clear()
        }
        // 槽位在配方编译期固定，循环外取一次即可。
        // 每颗每刻仍会新建一个 Vec3 与一个 Color：渲染粒子会持有这两个对象做插值端点，
        // 要再省下去得把 RenderParticle 的位置/颜色换成基本类型字段。
        val slots = e.runtime.slots
        val sR = slots.slot("r"); val sG = slots.slot("g"); val sB = slots.slot("b"); val sA = slots.slot("a")
        val sSc = slots.slot("sc"); val sGlow = slots.slot("glow"); val sLight = slots.slot("light")
        for (p in e.runtime.particles) {
            var member = e.members[p]
            if (member == null) {
                if (frame == null) continue
                val id = UUID.randomUUID()
                val look = ResolvedVisual.of(e.runtime.recipe.visual, 1f)
                frame.applyInto(p.x, p.y, p.z, posOut)
                engine.spawnParticle(id, posOut[0], posOut[1], posOut[2],
                    p.regs[sR].toFloat(), p.regs[sG].toFloat(), p.regs[sB].toFloat(), p.regs[sA].toFloat(),
                    p.regs[sSc].toFloat(), p.lifetime, null, p.regs[sGlow] != 0.0, p.regs[sLight].toInt(), look, e.runtime.recipe.lifeCurve)
                requireLocalized(engine.containsParticle(id)) { translation("error.recipe.client_limit") }
                engine.markSpawnedDuringRender(id, 0f)
                member = Member(id, frame, look)
                e.members[p] = member
            }
            // 速度拉丝：长轴对齐速度并按速度拉长，逐刻改写这颗粒子独占的朝向与尺寸副本。
            if (member.visual.alignVelocity) {
                val vx = p.regs[slots.slot("vx")]
                val vy = p.regs[slots.slot("vy")]
                val vz = p.regs[slots.slot("vz")]
                if (VelocityStretch.spinDegrees(vx, vy, vz, member.visual.spin)) {
                    val speed = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
                    val factor = VelocityStretch.lengthFactor(speed, member.visual.stretch)
                    member.visual.scaleArray?.let {
                        it[0] = factor.toFloat()
                        it[1] = (1.0 / factor).toFloat()
                    }
                }
            }
            val current = if (e.runtime.recipe.follow) frame ?: member.frame else member.frame
            current.applyInto(p.x, p.y, p.z, posOut)
            var alpha = if (e.runtime.recipe.follow && frame == null) 0f else p.regs[sA].toFloat()
            // 软粒子：贴近方块时按被挡比例淡出，消掉插进地面的硬边。
            if (member.visual.softFade > 0.0 && alpha > 0f) {
                alpha *= softAlpha(member.visual, posOut[0], posOut[1], posOut[2])
            }
            engine.applyRecipeFrame(member.id, posOut[0], posOut[1], posOut[2],
                p.regs[sR].toFloat(), p.regs[sG].toFloat(), p.regs[sB].toFloat(), alpha,
                p.regs[sSc].toFloat(), p.regs[sGlow] != 0.0, p.regs[sLight].toInt(), member.visual)
        }
    }

    /** 锚点偏移的落点，逐 tick 复用。 */
    private val orbitOut = DoubleArray(3)

    /**
     * 解析这一 tick 的锚点：先取基础锚点（固定坐标或实体），再加上 `orbit` 偏移公式。
     * 偏移在帧内按 partialTick 插值；这里每 tick 一次，取整刻值。
     */
    private fun resolve(e: Entry): Frame? {
        val a = e.anchor
        val base = if (a.entity == null) {
            Frame(a.position.add(a.offset).toMinecraft())
        } else {
            val level = Minecraft.getInstance().level ?: return null
            val entity = level.getEntity(a.entityId)?.takeIf { it.uuid == a.entity }
                ?: level.entitiesForRendering().firstOrNull { it.uuid == a.entity } ?: return null
            Frame(AttachMath.resolve(entity.position(), entity.yRot, entity.xRot, a.offset.toMinecraft(), a.local), entity.yRot, entity.xRot, a.local)
        }
        if (!e.runtime.hasOrbit) return base
        e.runtime.orbitOffset(orbitOut, 0f)
        return Frame(base.pos.add(orbitOut[0], orbitOut[1], orbitOut[2]), base.yaw, base.pitch, base.local)
    }

    private fun error(id: UUID, e: RuntimeException) {
        org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").error(translation("log.recipe.instance").resolve(), id, e.message)
        val detail = e.diagnostic()
        val message = if (detail == null) translation("error.recipe.stopped")
            else translation("error.recipe.stopped_detail", detail)
        Minecraft.getInstance().player?.sendSystemMessage(message.component())
    }

    private fun remove(id: UUID) {
        val e = sources.remove(id) ?: return
        ClientParticleEngine.instance()?.destroyParticles(e.members.values.map { it.id }.toTypedArray())
    }

    fun clear() = sources.clear()

    private val burstPos = DoubleArray(3)

    /** 在粒子最后的位置炸出 [sub] 指定的那一批。方向按索引与刻号做确定性散开。 */
    private fun burst(e: Entry, p: RecipeRuntime.Particle, member: Member, sub: SubEmit) {
        val engine = ClientParticleEngine.instance() ?: return
        member.frame.applyInto(p.x, p.y, p.z, burstPos)
        val x = burstPos[0]; val y = burstPos[1]; val z = burstPos[2]
        val slots = e.runtime.slots
        val r = p.regs[slots.slot("r")].toFloat()
        val g = p.regs[slots.slot("g")].toFloat()
        val b = p.regs[slots.slot("b")].toFloat()
        val scale = (p.regs[slots.slot("sc")] * sub.scale).toFloat()
        val look = ResolvedVisual.of(e.runtime.recipe.visual, sub.scale.toFloat())
        var seed = p.index * -7046029254386353131L xor (e.runtime.ticks * -4417276706812531889L)
        repeat(sub.count) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val u = ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val theta = ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * Math.PI * 2.0
            val ring = kotlin.math.sqrt((1.0 - u * u).coerceAtLeast(0.0))
            val vx = ring * kotlin.math.cos(theta) * sub.speed
            val vy = u * sub.speed
            val vz = ring * kotlin.math.sin(theta) * sub.speed
            val id = java.util.UUID.randomUUID()
            engine.spawnParticle(id, x, y, z, r, g, b, 1f, scale, sub.lifetime, null, false, 0, look)
            engine.setVelocity(id, vx, vy, vz)
        }
    }

    private val softOffsets = DoubleArray(work.nekow.particledrawing.api.SoftFade.FLOATS)

    /** 在粒子周围采样方块，返回透明度系数（1 = 完全可见）。 */
    private fun softAlpha(visual: ResolvedVisual, x: Double, y: Double, z: Double): Float {
        val radius = work.nekow.particledrawing.api.SoftFade.radiusOf(visual.softFade)
        if (radius <= 0.0) return 1f
        work.nekow.particledrawing.api.SoftFade.sampleOffsets(radius, softOffsets)
        var blocked = 0
        var i = 0
        while (i < softOffsets.size) {
            if (ClientParticleWorld.isSolid(x + softOffsets[i], y + softOffsets[i + 1], z + softOffsets[i + 2])) blocked++
            i += 3
        }
        return work.nekow.particledrawing.api.SoftFade.factor(blocked).toFloat()
    }
}
