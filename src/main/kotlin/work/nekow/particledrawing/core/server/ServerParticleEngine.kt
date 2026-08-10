package work.nekow.particledrawing.core.server

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.TransformOp
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.ParticleDestroyPayload
import work.nekow.particledrawing.core.network.ParticleGroupTransformPayload
import work.nekow.particledrawing.core.network.ParticleSpawnPayload
import work.nekow.particledrawing.core.network.ParticleUpdatePayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("unused")
class ServerParticleEngine(
    val dimensionId: UUID
) {

    private val particles: MutableMap<UUID, ParticleData> = ConcurrentHashMap()
    private val groups: MutableMap<UUID, ParticleGroupData> = ConcurrentHashMap()
    private val visibilityManager = ParticleVisibilityManager()
    private var tickCounter: Int = 0

    @Suppress("DataFlowIssue")
    fun spawnParticle(style: ParticleStyle, position: Vec3, color: Color,
                      scale: Float, lifetime: Int, groupId: UUID?,
                      glowing: Boolean, offsetFromPivot: Vec3?,
                      playersInDimension: Collection<ServerPlayer>): ParticleData {
        val id = UUID.randomUUID()
        val data = ParticleData.create(id, style, position, color, scale,
            lifetime, groupId, glowing, offsetFromPivot)
        particles[id] = data

        if (groupId != null) {
            groups[groupId]?.addMember(id)
        }

        val payload = ParticleSpawnPayload(
            id, style, position.x, position.y, position.z,
            color.r, color.g, color.b, color.a,
            scale, lifetime, groupId, glowing
        )

        sendToVisible(playersInDimension, position, payload)
        return data
    }

    fun updateParticle(id: UUID, position: Vec3, color: Color, scale: Float,
                       updatePos: Boolean, updateColor: Boolean, updateScale: Boolean,
                       durationTicks: Int, easing: EasingType,
                       playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return

        if (updatePos) data.setPosition(position)
        if (updateColor) data.setColor(color)
        if (updateScale) data.setScale(scale)

        val payload: ParticleUpdatePayload = when {
            updatePos && updateColor && updateScale -> ParticleUpdatePayload.full(id,
                position.x, position.y, position.z,
                color.r, color.g, color.b, color.a,
                scale, durationTicks, easing)
            updatePos -> ParticleUpdatePayload.positionOnly(id,
                position.x, position.y, position.z, durationTicks, easing)
            updateColor -> ParticleUpdatePayload.colorOnly(id,
                color.r, color.g, color.b, color.a, durationTicks, easing)
            else -> ParticleUpdatePayload.scaleOnly(id, scale, durationTicks, easing)
        }

        sendToVisible(playersInDimension, data.position(), payload)
    }

    // ===================================================================
    // UpdateBuilder — 链式调用更新粒子属性
    //
    // 用法:
    //   engine.update(particleId)
    //       .position(x, y, z)
    //       .color(Color.BLUE)
    //       .easing(EasingType.EASE_OUT, 10)
    //       .send(players)
    // ===================================================================

    inner class UpdateBuilder(private val id: UUID) {
        private var pos: Vec3? = null
        private var col: Color? = null
        private var scl: Float? = null
        private var dur: Int = 0
        private var ease: EasingType = EasingType.LINEAR

        fun position(x: Double, y: Double, z: Double): UpdateBuilder {
            pos = Vec3(x, y, z); return this
        }
        fun position(v: Vec3): UpdateBuilder { pos = v; return this }
        fun color(c: Color): UpdateBuilder { col = c; return this }
        fun scale(s: Float): UpdateBuilder { scl = s; return this }
        fun easing(e: EasingType, durationTicks: Int): UpdateBuilder { ease = e; dur = durationTicks; return this }
        fun duration(ticks: Int): UpdateBuilder { dur = ticks; return this }

        fun send(players: Collection<ServerPlayer>) {
            val data = particles[id] ?: return
            val p = pos ?: data.position()
            val c = col ?: data.color()
            val s = scl ?: data.scale()
            updateParticle(id, p, c, s,
                updatePos = pos != null,
                updateColor = col != null,
                updateScale = scl != null,
                durationTicks = dur, easing = ease,
                playersInDimension = players)
        }
    }

    fun update(id: UUID) = UpdateBuilder(id)

    fun applyGroupTransform(groupId: UUID, transformType: TransformOp.Type,
                            delta: Vec3, axis: Vec3, radians: Double,
                            targetColor: Color, targetScale: Float,
                            pivot: Vec3?, durationTicks: Int, easing: EasingType,
                            playersInDimension: Collection<ServerPlayer>) {
        val group = groups[groupId] ?: return

        val groupParticles = ArrayList<ParticleData>()
        for (memberId in group.memberIds()) {
            particles[memberId]?.let { groupParticles.add(it) }
        }

        val groupPivot = pivot ?: group.pivot()

        when (transformType) {
            TransformOp.Type.TRANSLATE -> {
                for (p in groupParticles) {
                    p.setPosition(p.position().add(delta))
                }
            }
            TransformOp.Type.ROTATE -> {
                val nAxis = axis.normalize()
                for (p in groupParticles) {
                    val rel = p.position().subtract(groupPivot)
                    val rotated = rotateAroundAxis(rel, nAxis, radians)
                    p.setPosition(groupPivot.add(rotated))
                    p.setOffsetFromPivot(rotated)
                }
            }
            TransformOp.Type.RECOLOR -> {
                for (p in groupParticles) {
                    p.setColor(targetColor)
                }
            }
            TransformOp.Type.SCALE -> {
                for (p in groupParticles) {
                    val rel = p.offsetFromPivot()
                    val scaled = rel.scale(targetScale.toDouble())
                    p.setPosition(groupPivot.add(scaled))
                    p.setOffsetFromPivot(scaled)
                    p.setScale(targetScale)
                }
            }
        }

        val payload: ParticleGroupTransformPayload = when (transformType) {
            TransformOp.Type.TRANSLATE -> ParticleGroupTransformPayload.translate(
                groupId, delta.x, delta.y, delta.z,
                groupPivot.x, groupPivot.y, groupPivot.z,
                durationTicks, easing)
            TransformOp.Type.ROTATE -> ParticleGroupTransformPayload.rotate(
                groupId, axis.x, axis.y, axis.z, radians,
                groupPivot.x, groupPivot.y, groupPivot.z,
                durationTicks, easing)
            TransformOp.Type.RECOLOR -> ParticleGroupTransformPayload.recolor(
                groupId, targetColor.r, targetColor.g, targetColor.b, targetColor.a,
                durationTicks, easing)
            TransformOp.Type.SCALE -> ParticleGroupTransformPayload.scale(
                groupId, targetScale, groupPivot.x, groupPivot.y, groupPivot.z,
                durationTicks, easing)
        }

        for (player in playersInDimension) {
            PacketDistributor.sendToPlayer(player, payload)
        }
    }

    fun destroyParticle(id: UUID, playersInDimension: Collection<ServerPlayer>) {
        val data = particles.remove(id) ?: return

        val groupId = data.groupId
        if (groupId != null) {
            groups[groupId]?.removeMember(id)
        }

        val payload = ParticleDestroyPayload.single(id)
        sendToAllInDimension(playersInDimension, payload)
    }

    fun destroyGroup(groupId: UUID, playersInDimension: Collection<ServerPlayer>) {
        val group = groups.remove(groupId) ?: return

        val ids = ArrayList(group.memberIds())
        for (id in ids) {
            particles.remove(id)
        }

        val payload = ParticleDestroyPayload.group(groupId, ids)
        sendToAllInDimension(playersInDimension, payload)
    }

    fun tick(playersInDimension: Collection<ServerPlayer>) {
        tickCounter++

        val it = particles.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val data = entry.value
            data.tick()
            if (data.isExpired()) {
                val groupId = data.groupId
                if (groupId != null) {
                    groups[groupId]?.removeMember(entry.key)
                }
                val payload = ParticleDestroyPayload.single(entry.key)
                if (playersInDimension.isNotEmpty()) {
                    sendToAllInDimension(playersInDimension, payload)
                }
                it.remove()
            }
        }

        groups.entries.removeIf { it.value.isEmpty() }

        val interval = ParticleDrawingConfig.SERVER.visibilityCheckInterval.get()
        if (tickCounter % interval == 0) {
            visibilityManager.updateVisibility(particles.values, playersInDimension,
                ParticleDrawingConfig.SERVER.visibilityRadius.get())
        }
    }

    fun particleCount(): Int = particles.size
    fun groupCount(): Int = groups.size

    fun getGroup(groupId: UUID): ParticleGroupData? = groups[groupId]

    @Suppress("unused")
    fun createGroup(groupId: UUID, pivot: Vec3): ParticleGroupData {
        val group = ParticleGroupData.create(groupId, pivot)
        groups[groupId] = group
        return group
    }

    fun getParticle(id: UUID): ParticleData? = particles[id]

    fun setOffsetFromPivot(id: UUID, offset: Vec3) {
        particles[id]?.setOffsetFromPivot(offset)
    }

    fun clearAll(playersInDimension: Collection<ServerPlayer>): Int {
        val count = particles.size

        if (particles.isNotEmpty()) {
            val allIds = particles.keys.toTypedArray()
            val batchSize = 1000

            var offset = 0
            while (offset < allIds.size) {
                val end = Math.min(offset + batchSize, allIds.size)
                val batch = java.util.Arrays.copyOfRange(allIds, offset, end)
                val payload = ParticleDestroyPayload(batch, null)

                for (player in playersInDimension) {
                    PacketDistributor.sendToPlayer(player, payload)
                }
                offset += batchSize
            }
        }

        particles.clear()
        groups.clear()
        return count
    }

    private fun sendToVisible(players: Collection<ServerPlayer>, position: Vec3,
                              payload: CustomPacketPayload) {
        val radius = ParticleDrawingConfig.SERVER.visibilityRadius.get()
        for (player in players) {
            if (visibilityManager.isWithinRange(player, position, radius)) {
                PacketDistributor.sendToPlayer(player, payload)
            }
        }
    }

    private fun sendToAllInDimension(players: Collection<ServerPlayer>, payload: CustomPacketPayload) {
        for (player in players) {
            PacketDistributor.sendToPlayer(player, payload)
        }
    }

    companion object {
        private val DIMENSION_ENGINES: MutableMap<UUID, ServerParticleEngine> = ConcurrentHashMap()

        fun getOrCreate(dimensionId: UUID): ServerParticleEngine {
            return DIMENSION_ENGINES.computeIfAbsent(dimensionId) { ServerParticleEngine(it) }
        }

        fun get(dimensionId: UUID): ServerParticleEngine? = DIMENSION_ENGINES[dimensionId]

        fun clearDimension(dimensionId: UUID) {
            DIMENSION_ENGINES.remove(dimensionId)
        }

        private fun rotateAroundAxis(v: Vec3, axis: Vec3, radians: Double): Vec3 {
            val cos = Math.cos(radians)
            val sin = Math.sin(radians)
            val dot = v.dot(axis)
            val cross = axis.cross(v)
            return Vec3(
                v.x * cos + cross.x * sin + axis.x * dot * (1 - cos),
                v.y * cos + cross.y * sin + axis.y * dot * (1 - cos),
                v.z * cos + cross.z * sin + axis.z * dot * (1 - cos)
            )
        }
    }
}
