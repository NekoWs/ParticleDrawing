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
import work.nekow.particledrawing.core.motion.MotionPayload
import work.nekow.particledrawing.core.motion.rotateAround
import work.nekow.particledrawing.core.network.ParticleDestroyPayload
import work.nekow.particledrawing.core.network.ParticleGroupTransformPayload
import work.nekow.particledrawing.core.network.ParticleSpawnPayload
import work.nekow.particledrawing.core.network.ParticleUpdatePayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

private val LOGGER: Logger = LogManager.getLogger("ParticleDrawing")

/**
 * 服务端权威粒子引擎，每个维度一个实例。
 * 管理粒子生命周期、可见性和网络同步。
 *
 * @param dimensionId 所属维度的唯一标识符
 */
@Suppress("unused")
class ServerParticleEngine(
    val dimensionId: UUID
) {

    private val particles: MutableMap<UUID, ParticleData> = ConcurrentHashMap()
    private val groups: MutableMap<UUID, ParticleGroupData> = ConcurrentHashMap()

    // 粒子 -> 已同步的玩家集合；玩家 -> 已同步的粒子集合（用于每玩家粒子数限制与可见性重检）
    private val visibleTo: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap()
    private val playerParticles: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap()
    private var visibilityTickCounter = 0
    private var lastCapacityWarnNanos = 0L

    /**
     * 生成粒子并广播到视野内可见的玩家。
     *
     * @param style 粒子视觉效果
     * @param position 世界坐标
     * @param color RGBA 颜色
     * @param scale 粒子缩放
     * @param lifetime 存活 tick 数，-1 为永生
     * @param groupId 所属组 ID，可为 null
     * @param glowing 是否发光
     * @param offsetFromPivot 相对轴心的偏移，可为 null
     * @param playersInDimension 维度内的玩家列表
     * @return 创建的粒子数据；达到维度上限时为 null
     */
    @Suppress("DataFlowIssue")
    fun spawnParticle(style: ParticleStyle, position: Vec3, color: Color,
                      scale: Float, lifetime: Int, groupId: UUID?,
                      glowing: Boolean, offsetFromPivot: Vec3?,
                      playersInDimension: Collection<ServerPlayer>): ParticleData? {
        val maxTotal = ParticleDrawingConfig.SERVER.maxParticlesPerDimension.get()
        if (particles.size >= maxTotal) {
            warnWhenOverCapacity()
            return null
        }

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

        broadcastSpawn(playersInDimension, position, id, payload)
        return data
    }

    /**
     * 更新粒子属性（位置、颜色、缩放）并广播。
     *
     * @param id 粒子 ID
     * @param position 新位置
     * @param color 新颜色
     * @param scale 新缩放
     * @param updatePos 是否更新位置
     * @param updateColor 是否更新颜色
     * @param updateScale 是否更新缩放
     * @param durationTicks 过渡持续 tick 数
     * @param easing 缓动类型
     * @param playersInDimension 维度内的玩家列表
     */
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

    /**
     * 链式调用更新粒子属性。
     *
     * 用法:
     * ```
     * engine.update(particleId)
     *     .position(x, y, z)
     *     .color(Color.BLUE)
     *     .easing(EasingType.EASE_OUT, 10)
     *     .send(players)
     * ```
     *
     * @param id 要更新的粒子 ID
     */
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

    /**
     * 创建粒子的链式更新构建器。
     *
     * @param id 要更新的粒子 ID
     * @return 更新构建器实例
     */
    fun update(id: UUID) = UpdateBuilder(id)

    /**
     * 对组内所有粒子应用变换（平移、旋转、变色、缩放）。
     *
     * @param groupId 组 ID
     * @param transformType 变换类型
     * @param delta 平移向量
     * @param axis 旋转轴
     * @param radians 旋转弧度
     * @param targetColor 目标颜色
     * @param targetScale 目标缩放
     * @param pivot 变换轴心，可为 null
     * @param durationTicks 过渡持续 tick 数
     * @param easing 缓动类型
     * @param playersInDimension 维度内的玩家列表
     */
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
                    val rotated = rel.rotateAround(nAxis, radians)
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

    /**
     * 销毁单个粒子并通知所有维度内玩家。
     *
     * @param id 粒子 ID
     * @param playersInDimension 维度内的玩家列表
     */
    fun destroyParticle(id: UUID, playersInDimension: Collection<ServerPlayer>) {
        val data = particles.remove(id) ?: return

        val groupId = data.groupId
        if (groupId != null) {
            groups[groupId]?.removeMember(id)
        }

        val payload = ParticleDestroyPayload.single(id)
        sendToAllInDimension(playersInDimension, payload)
        untrackParticle(id)
    }

    /**
     * 销毁整个粒子组及其所有成员。
     *
     * @param groupId 组 ID
     * @param playersInDimension 维度内的玩家列表
     */
    fun destroyGroup(groupId: UUID, playersInDimension: Collection<ServerPlayer>) {
        val group = groups.remove(groupId) ?: return

        val ids = ArrayList(group.memberIds())
        for (id in ids) {
            particles.remove(id)
        }

        val payload = ParticleDestroyPayload.group(groupId, ids)
        sendToAllInDimension(playersInDimension, payload)
        untrackParticles(ids)
    }

    /**
     * 每 tick 更新：推进生命周期、移除过期粒子。
     *
     * @param playersInDimension 维度内的玩家列表
     */
    fun tick(playersInDimension: Collection<ServerPlayer>) {
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
                sendToAllInDimension(playersInDimension, payload)
                untrackParticle(entry.key)
                it.remove()
            }
        }

        groups.entries.removeIf { it.value.isEmpty() }

        // 周期性可见性重检：补发新进入范围的粒子、回收已越界的粒子
        visibilityTickCounter++
        if (visibilityTickCounter >= ParticleDrawingConfig.SERVER.visibilityCheckInterval.get().coerceAtLeast(1)) {
            visibilityTickCounter = 0
            recheckVisibility(playersInDimension)
        }

        // 清理已离开维度的玩家追踪记录
        pruneStalePlayers(playersInDimension)
    }

    /** @return 当前活跃粒子总数 */
    fun particleCount(): Int = particles.size
    /** @return 当前组总数 */
    fun groupCount(): Int = groups.size

    /** @return 指定 ID 的组，不存在则返回 null */
    fun getGroup(groupId: UUID): ParticleGroupData? = groups[groupId]

    /**
     * 创建粒子组。
     *
     * @param groupId 组 ID
     * @param pivot 组轴心
     * @return 创建的组数据
     */
    @Suppress("unused")
    fun createGroup(groupId: UUID, pivot: Vec3): ParticleGroupData {
        val group = ParticleGroupData.create(groupId, pivot)
        groups[groupId] = group
        return group
    }

    /** @return 指定 ID 的粒子数据，不存在则返回 null */
    fun getParticle(id: UUID): ParticleData? = particles[id]

    /**
     * 设置粒子相对轴心的偏移。
     *
     * @param id 粒子 ID
     * @param offset 偏移向量
     */
    fun setOffsetFromPivot(id: UUID, offset: Vec3) {
        particles[id]?.setOffsetFromPivot(offset)
    }

    /**
     * 清除维度内所有粒子和组，分批发送销毁通知。
     *
     * @param playersInDimension 维度内的玩家列表
     * @return 清除的粒子数量
     */
    fun clearAll(playersInDimension: Collection<ServerPlayer>): Int {
        val count = particles.size

        if (particles.isNotEmpty()) {
            val allIds = particles.keys.toTypedArray()
            val batchSize = 1000

            var offset = 0
            while (offset < allIds.size) {
                val end = (offset + batchSize).coerceAtMost(allIds.size)
                val batch = allIds.copyOfRange(offset, end)
                val payload = ParticleDestroyPayload(batch, null)

                for (player in playersInDimension) {
                    PacketDistributor.sendToPlayer(player, payload)
                }
                offset += batchSize
            }
        }

        particles.clear()
        groups.clear()
        playerParticles.clear()
        visibleTo.clear()
        return count
    }

    private fun track(playerId: UUID, particleId: UUID) {
        playerParticles.computeIfAbsent(playerId) { ConcurrentHashMap.newKeySet() }.add(particleId)
        visibleTo.computeIfAbsent(particleId) { ConcurrentHashMap.newKeySet() }.add(playerId)
    }

    private fun untrackParticle(particleId: UUID) {
        val playerIds = visibleTo.remove(particleId) ?: return
        for (playerId in playerIds) {
            playerParticles[playerId]?.remove(particleId)
        }
    }

    private fun untrackParticles(particleIds: Collection<UUID>) {
        for (particleId in particleIds) untrackParticle(particleId)
    }

    private fun broadcastSpawn(players: Collection<ServerPlayer>, position: Vec3,
                               particleId: UUID, payload: CustomPacketPayload) {
        val radius = ParticleDrawingConfig.SERVER.visibilityRadius.get()
        val maxPerPlayer = ParticleDrawingConfig.SERVER.maxParticlesPerPlayer.get()

        for (player in players) {
            if (!ParticleVisibilityManager.isWithinRange(player, position, radius)) continue

            val trackedCount = playerParticles[player.uuid]?.size ?: 0
            if (trackedCount >= maxPerPlayer) continue

            PacketDistributor.sendToPlayer(player, payload)
            track(player.uuid, particleId)
        }
    }

    private fun recheckVisibility(players: Collection<ServerPlayer>) {
        if (players.isEmpty()) return
        val radius = ParticleDrawingConfig.SERVER.visibilityRadius.get()
        val maxPerPlayer = ParticleDrawingConfig.SERVER.maxParticlesPerPlayer.get()

        for (player in players) {
            val tracked = playerParticles[player.uuid] ?: continue

            val toRemove = ArrayList<UUID>()
            for (particleId in tracked) {
                val data = particles[particleId]
                if (data == null || !ParticleVisibilityManager.isWithinRange(player, data.position(), radius)) {
                    toRemove.add(particleId)
                }
            }
            for (particleId in toRemove) {
                tracked.remove(particleId)
                visibleTo[particleId]?.remove(player.uuid)
                PacketDistributor.sendToPlayer(player, ParticleDestroyPayload.single(particleId))
            }

            var count = tracked.size
            if (count < maxPerPlayer) {
                for ((particleId, data) in particles) {
                    if (count >= maxPerPlayer) break
                    if (tracked.contains(particleId)) continue
                    if (!ParticleVisibilityManager.isWithinRange(player, data.position(), radius)) continue

                    PacketDistributor.sendToPlayer(player, spawnPayload(data))
                    track(player.uuid, particleId)
                    count++
                }
            }
        }
    }

    private fun spawnPayload(data: ParticleData): ParticleSpawnPayload {
        return ParticleSpawnPayload(
            data.id, data.style,
            data.position().x, data.position().y, data.position().z,
            data.color().r, data.color().g, data.color().b, data.color().a,
            data.scale(), data.lifetime(), data.groupId, data.glowing()
        )
    }

    private fun pruneStalePlayers(players: Collection<ServerPlayer>) {
        if (players.isEmpty()) {
            playerParticles.clear()
            visibleTo.clear()
            return
        }
        val active = HashSet<UUID>(players.size)
        for (player in players) active.add(player.uuid)

        val it = playerParticles.keys.iterator()
        while (it.hasNext()) {
            val playerId = it.next()
            if (playerId !in active) {
                val ids = playerParticles.remove(playerId)
                if (ids != null) {
                    for (particleId in ids) {
                        visibleTo[particleId]?.remove(playerId)
                    }
                }
            }
        }
    }

    private fun warnWhenOverCapacity() {
        val now = System.nanoTime()
        if (now - lastCapacityWarnNanos > 1_000_000_000L) {
            lastCapacityWarnNanos = now
            LOGGER.warn("Particle limit reached in dimension {}: cannot spawn more than {} particles",
                dimensionId, ParticleDrawingConfig.SERVER.maxParticlesPerDimension.get())
        }
    }

    private fun sendToVisible(players: Collection<ServerPlayer>, position: Vec3,
                              payload: CustomPacketPayload) {
        val radius = ParticleDrawingConfig.SERVER.visibilityRadius.get()
        for (player in players) {
            if (ParticleVisibilityManager.isWithinRange(player, position, radius)) {
                PacketDistributor.sendToPlayer(player, payload)
            }
        }
    }

    private fun sendToAllInDimension(players: Collection<ServerPlayer>, payload: CustomPacketPayload) {
        for (player in players) {
            PacketDistributor.sendToPlayer(player, payload)
        }
    }

    fun sendMotion(groupId: UUID, active: Boolean, algorithmId: String,
                    params: DoubleArray, pivot: Vec3,
                    playersInDimension: Collection<ServerPlayer>) {
        val payload = MotionPayload(groupId, active, algorithmId, params,
            pivot.x, pivot.y, pivot.z)
        sendToAllInDimension(playersInDimension, payload)
    }

    companion object {
        /** 全局维度引擎映射表 */
        private val DIMENSION_ENGINES: MutableMap<UUID, ServerParticleEngine> = ConcurrentHashMap()

        /**
         * 获取或创建指定维度的引擎实例。
         *
         * @param dimensionId 维度 ID
         * @return 引擎实例
         */
        fun getOrCreate(dimensionId: UUID): ServerParticleEngine {
            return DIMENSION_ENGINES.computeIfAbsent(dimensionId) { ServerParticleEngine(it) }
        }

        /** @return 指定维度的引擎，不存在则返回 null */
        fun get(dimensionId: UUID): ServerParticleEngine? = DIMENSION_ENGINES[dimensionId]

        /** 清除指定维度的引擎实例 */
        fun clearDimension(dimensionId: UUID) {
            DIMENSION_ENGINES.remove(dimensionId)
        }
    }
}
