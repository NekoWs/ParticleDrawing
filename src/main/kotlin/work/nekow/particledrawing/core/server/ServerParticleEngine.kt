package work.nekow.particledrawing.core.server

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleSpawnSpec
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.ParticleDestroyPayload
import work.nekow.particledrawing.core.network.ParticleAttachPayload
import work.nekow.particledrawing.core.network.ParticleForcePayload
import work.nekow.particledrawing.core.network.ParticleForceBatchPayload
import work.nekow.particledrawing.core.network.ParticleLightLevelPayload
import work.nekow.particledrawing.core.network.ParticleRotationPayload
import work.nekow.particledrawing.core.network.ParticleSetPositionPayload
import work.nekow.particledrawing.core.network.ParticleSpawnBatchPayload
import work.nekow.particledrawing.core.network.ParticleSpawnPayload
import work.nekow.particledrawing.core.network.ParticleTrackBatchPayload
import work.nekow.particledrawing.core.network.ParticleTrackPayload
import work.nekow.particledrawing.core.network.ParticleTranslatePayload
import work.nekow.particledrawing.core.network.ParticleUpdatePayload
import work.nekow.particledrawing.core.network.ParticleVelocityPayload
import work.nekow.particledrawing.core.network.ParticleVelocityBatchPayload
import work.nekow.particledrawing.util.AttachMath
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

private val LOGGER: Logger = LogManager.getLogger("ParticleDrawing")

// 服务端权威粒子引擎，每个维度一个实例（经 getOrCreate 获取），负责粒子与粒子组的生命周期与网络同步。
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
     * 生成粒子并广播到视野内可见的玩家；达到维度上限时返回 null。
     *
     * [id] 由调用方给（[work.nekow.particledrawing.api.ParticleHandle.Builder] 先分配再可能延迟生成）；
     * [visual] 是生成时定死的外观规格，[lifeCurve] 是寿命曲线，[prev] 是首帧插值端点（可为 null）。
     */
    @Suppress("DataFlowIssue")
    fun spawnParticle(id: UUID, position: Vec3, color: Color,
                      scale: Float, lifetime: Int, groupId: UUID?,
                      glowing: Boolean, lightLevel: Int, offsetFromPivot: Vec3?,
                      playersInDimension: Collection<ServerPlayer>,
                      visual: ParticleVisual? = null,
                      lifeCurve: ParticleLifeCurve? = null,
                      prev: Vec3? = null): ParticleData? {
        val maxTotal = ParticleDrawingConfig.SERVER.maxParticlesPerDimension.get()
        if (particles.size >= maxTotal) {
            warnWhenOverCapacity()
            return null
        }

        val data = ParticleData.create(id, position, color, scale,
            lifetime, groupId, glowing, lightLevel, offsetFromPivot, visual, lifeCurve)
        particles[id] = data

        if (groupId != null) {
            groups[groupId]?.addMember(id)
        }

        val payload = ParticleSpawnPayload(
            id, position.x, position.y, position.z,
            color.r, color.g, color.b, color.a,
            scale, lifetime, groupId, glowing, lightLevel, visual, lifeCurve, prev,
        )

        broadcastSpawn(playersInDimension, position, id, payload)
        return data
    }

    /**
     * 批量生成：先逐条登记（超出维度上限的给 null），再**逐玩家按可见性裁剪成一包**下发。
     *
     * 与逐颗 [spawnParticle] 的差别只在带宽：每条记录的字段布局完全一致，客户端落地走同一条路径。
     * 返回与 [specs] 一一对应的粒子数据（被拒绝的为 null）。
     */
    fun spawnParticles(specs: List<ParticleSpawnSpec>,
                       playersInDimension: Collection<ServerPlayer>,
                       groupId: UUID? = null): List<ParticleData?> {
        if (specs.isEmpty()) return emptyList()
        val maxTotal = ParticleDrawingConfig.SERVER.maxParticlesPerDimension.get()

        val created = ArrayList<ParticleData?>(specs.size)
        val payloads = ArrayList<ParticleSpawnPayload>(specs.size)
        for (spec in specs) {
            if (particles.size >= maxTotal) {
                warnWhenOverCapacity()
                created.add(null)
                continue
            }
            val id = UUID.randomUUID()
            val data = ParticleData.create(
                id, spec.position, spec.color, spec.scale, spec.lifetime,
                groupId, spec.glowing, spec.lightLevel, null, spec.visual, spec.resolvedLifeCurve(),
            )
            particles[id] = data
            if (groupId != null) groups[groupId]?.addMember(id)
            created.add(data)
            payloads.add(
                ParticleSpawnPayload(
                    id, spec.position.x, spec.position.y, spec.position.z,
                    spec.color.r, spec.color.g, spec.color.b, spec.color.a,
                    spec.scale, spec.lifetime, groupId, spec.glowing, spec.lightLevel,
                    spec.visual, spec.resolvedLifeCurve(), spec.prev,
                )
            )
        }

        // 逐玩家裁剪：可见性与每玩家上限都和单发同一套判定，然后按 MAX_BATCH 拆包
        val maxPerPlayer = ParticleDrawingConfig.SERVER.maxParticlesPerPlayer.get()
        for (player in playersInDimension) {
            val batch = ArrayList<ParticleSpawnPayload>(payloads.size)
            for (payload in payloads) {
                if (!ParticleVisibilityManager.isWithinViewDistance(player, payload.position())) continue
                val trackedCount = (playerParticles[player.uuid]?.size ?: 0) + batch.size
                if (trackedCount >= maxPerPlayer) break
                batch.add(payload)
            }
            var index = 0
            while (index < batch.size) {
                val end = minOf(index + ParticleSpawnBatchPayload.MAX_BATCH, batch.size)
                val chunk = batch.subList(index, end)
                PacketDistributor.sendToPlayer(player, ParticleSpawnBatchPayload(chunk.toList()))
                for (payload in chunk) track(player.uuid, payload.particleId)
                index = end
            }
        }
        return created
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

        if (updatePos) takeOverPosition(data, position)
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
     * 设置粒子的速度向量并广播（速度驱动接管位置，实体锚点解除）。
     * @param id 粒子 ID
     * @param velocity 速度向量（blocks/tick）
     * @param playersInDimension 维度内的玩家列表
     */
    fun setVelocity(id: UUID, velocity: Vec3, playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return
        data.setVelocity(velocity)

        val payload = ParticleVelocityPayload(id, velocity.x, velocity.y, velocity.z)
        sendToVisible(playersInDimension, data.position(), payload)
    }

    /**
     * 批量设置速度并广播：一个包覆盖多颗粒子，语义与 [setVelocity] 相同
     * （速度驱动接管位置、解除实体锚点、之后两端同规则逐 tick 积分）。
     * 逐玩家按可见性裁剪后再发，不因为合并成包就放松坐标可见性。
     *
     * @param ids 粒子 ID 列表
     * @param velocities 与 [ids] 按顺序一一对应的速度（blocks/tick）
     * @return 服务端实际生效的粒子数
     */
    fun setVelocities(ids: List<UUID>, velocities: List<Vec3>,
                      playersInDimension: Collection<ServerPlayer>): Int {
        val applied = LinkedHashMap<UUID, Vec3>()
        for (i in 0 until minOf(ids.size, velocities.size)) {
            val data = particles[ids[i]] ?: continue
            data.setVelocity(velocities[i])
            applied[ids[i]] = velocities[i]
        }
        sendMotionBatch(playersInDimension, applied) { visible ->
            ParticleVelocityBatchPayload(visible.map { (id, v) ->
                ParticleVelocityBatchPayload.Update(id, v.x, v.y, v.z)
            })
        }
        return applied.size
    }

    /**
     * 批量施力并广播：一个包覆盖多颗粒子，语义与 [applyForce] 相同（力驱动接管位置、
     * 解除实体锚点、两端同规则逐 tick 积分），每颗粒子各自的加速度、共用一个 [ticks]。
     * 逐玩家按可见性裁剪后再发。
     *
     * @param ids 粒子 ID 列表
     * @param accelerations 与 [ids] 按顺序一一对应的加速度（blocks/tick²）
     * @param ticks >0 = 施加这么多 tick；<0 = 无限；0 = 清除
     * @return 服务端实际生效的粒子数
     */
    fun applyForces(ids: List<UUID>, accelerations: List<Vec3>, ticks: Int,
                    playersInDimension: Collection<ServerPlayer>): Int {
        val applied = LinkedHashMap<UUID, Vec3>()
        for (i in 0 until minOf(ids.size, accelerations.size)) {
            val data = particles[ids[i]] ?: continue
            data.setAcceleration(accelerations[i], ticks)
            applied[ids[i]] = accelerations[i]
        }
        sendMotionBatch(playersInDimension, applied) { visible ->
            ParticleForceBatchPayload(ticks, visible.map { (id, a) ->
                ParticleForceBatchPayload.Update(id, a.x, a.y, a.z)
            })
        }
        return applied.size
    }

    /**
     * 批量运动指令（速度/力）的下发：逐玩家按可见性裁剪后合成一包。
     * 与 [trackParticles] 同一口径——包里的坐标同样敏感，不可见的玩家一颗都不发。
     */
    private fun sendMotionBatch(playersInDimension: Collection<ServerPlayer>,
                                values: Map<UUID, Vec3>,
                                payloadOf: (List<Pair<UUID, Vec3>>) -> CustomPacketPayload) {
        if (values.isEmpty()) return
        for (player in playersInDimension) {
            val visible = ArrayList<Pair<UUID, Vec3>>(values.size)
            for ((id, value) in values) {
                val data = particles[id] ?: continue
                if (!ParticleVisibilityManager.isWithinViewDistance(player, data.position())) continue
                visible.add(id to value)
            }
            if (visible.isNotEmpty()) {
                PacketDistributor.sendToPlayer(player, payloadOf(visible))
            }
        }
    }

    /**
     * 直设粒子位置并广播（无缓动）：客户端每个 tick 消费一条，用 partialTick 在相邻两条之间插值。
     * 位置指令接管运动，速度与力一并清零。
     * 供「每 tick 跟随一个非实体点」的粒子（如投射物本体）使用。
     */
    fun trackParticle(id: UUID, position: Vec3, playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return
        takeOverPosition(data, position)

        val payload = ParticleTrackPayload(id, position.x, position.y, position.z)
        sendToVisible(playersInDimension, data.position(), payload)
    }

    /**
     * 批量直设位置并广播：一个包覆盖多颗粒子，语义与 [trackParticle] 相同。
     * 逐玩家按可见性裁剪后再发，不因为合并成包就放松坐标可见性。
     *
     * @return 服务端实际生效的粒子数
     */
    fun trackParticles(ids: List<UUID>, positions: List<Vec3>,
                       playersInDimension: Collection<ServerPlayer>): Int {
        val count = minOf(ids.size, positions.size)
        if (count == 0) return 0

        val moved = HashSet<UUID>(count)
        for (i in 0 until count) {
            val data = particles[ids[i]] ?: continue
            takeOverPosition(data, positions[i])
            moved.add(ids[i])
        }
        if (moved.isEmpty()) return 0

        for (player in playersInDimension) {
            val visible = ArrayList<ParticleTrackBatchPayload.Track>(moved.size)
            for (i in 0 until count) {
                val id = ids[i]
                if (id !in moved) continue
                val data = particles[id] ?: continue
                if (!ParticleVisibilityManager.isWithinViewDistance(player, data.position())) continue
                val p = positions[i]
                visible.add(ParticleTrackBatchPayload.Track(id, p.x, p.y, p.z))
            }
            if (visible.isNotEmpty()) {
                PacketDistributor.sendToPlayer(player, ParticleTrackBatchPayload(visible))
            }
        }
        return moved.size
    }

    /**
     * 设置粒子的加速度（服务端权威力）并广播一次：之后两端按同一规则逐 tick 积分
     * （速度 += 加速度，位置 += 速度），中途不再发包。适合大量粒子沿同一个力运动。
     *
     * @param ticks >0 = 施加这么多 tick；<0 = 无限（直到被下一次力/速度/位置指令覆盖）；0 = 清除
     */
    fun applyForce(id: UUID, acceleration: Vec3, ticks: Int,
                   playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return
        data.setAcceleration(acceleration, ticks)

        val payload = ParticleForcePayload(id, acceleration.x, acceleration.y, acceleration.z, ticks)
        sendToVisible(playersInDimension, data.position(), payload)
    }

    /**
     * 把粒子挂到实体锚点上并广播一次：客户端按实体身份本地解析位置（[local] 为 true 时连朝向），
     * 服务端只在这里更新锚点，之后不再为它发位置包。锚点接管位置，速度与力清零。
     *
     * @param entityId 当拍解析到的网络 id；未解析到时给 [AttachMath.noEntity]
     * @param entityUuid 实体 UUID（主身份；客户端优先按它解析，网络 id 只是兜底）
     */
    fun attachParticle(id: UUID, entityId: Int, entityUuid: UUID?, offset: Vec3, local: Boolean,
                       playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return
        data.attach(entityId, entityUuid, offset, local)

        val payload = ParticleAttachPayload(id, entityId, entityUuid, offset.x, offset.y, offset.z, local)
        sendToVisible(playersInDimension, data.position(), payload)
    }

    /** 只按网络 id 挂载。 */
    fun attachParticle(id: UUID, entityId: Int, offset: Vec3, local: Boolean,
                       playersInDimension: Collection<ServerPlayer>) {
        attachParticle(id, entityId, null, offset, local, playersInDimension)
    }

    /** 解析锚点实体：优先按 uuid（网络 id 会随实体重载/换维度变化），解析到就刷新 id 缓存。 */
    private fun resolveAttached(level: ServerLevel?, data: ParticleData): Entity? {
        if (level == null) return null
        val uuid = data.attachedUuid()
        if (uuid != null) {
            val byUuid = level.getEntity(uuid)
            if (byUuid != null) {
                data.refreshAttachedEntityId(byUuid.id)
                return byUuid
            }
        }
        val id = data.attachedEntityId()
        return if (id == AttachMath.noEntity()) null else level.getEntity(id)
    }

    /** 位置指令接管：解除实体锚点，清零速度与力（与服务端 tick、客户端渲染粒子的口径一致）。 */
    private fun takeOverPosition(data: ParticleData, position: Vec3) {
        data.setVelocity(Vec3.ZERO) // 速度指令接管位置，锚点随之解除
        data.setAcceleration(Vec3.ZERO, 0)
        data.setPosition(position)
    }

    /**
     * 设置粒子的旋转（绕轴心做圆弧运动）并广播。
     * @param id 粒子 ID
     * @param pivot 旋转轴心（绝对世界坐标）
     * @param offset 粒子相对轴心的偏移向量
     * @param rot 目标欧拉角（弧度，X→Y→Z）
     */
    fun rotateParticle(id: UUID, pivot: Vec3, offset: Vec3, rot: DoubleArray,
                       durationTicks: Int, easing: EasingType,
                       playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return
        val payload = ParticleRotationPayload.of(id, pivot, offset, rot, durationTicks, easing)
        sendToVisible(playersInDimension, data.position(), payload)
    }

    /**
     * 设置粒子的平移（绕轴心叠加世界空间增量）并广播。
     * @param id 粒子 ID
     * @param pivot 旋转轴心（绝对世界坐标）
     * @param offset 粒子相对轴心的偏移向量
     * @param delta 目标平移增量
     */
    fun translateParticle(id: UUID, pivot: Vec3, offset: Vec3, delta: Vec3,
                          durationTicks: Int, easing: EasingType,
                          playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return
        val payload = ParticleTranslatePayload.of(id, pivot, offset, delta, durationTicks, easing)
        sendToVisible(playersInDimension, data.position(), payload)
    }

    /**
     * 设置粒子的位置（组 set 位置轨道）并广播：缓动未旋转偏移，保留旋转。
     * @param id 粒子 ID
     * @param pivot 旋转轴心（绝对世界坐标）
     * @param offset 目标未旋转偏移（相对轴心）
     */
    fun setPosition(id: UUID, pivot: Vec3, offset: Vec3,
                    durationTicks: Int, easing: EasingType,
                    playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return
        val payload = ParticleSetPositionPayload.of(id, pivot, offset, durationTicks, easing)
        sendToVisible(playersInDimension, data.position(), payload)
    }

    /**
     * 动态修改粒子的发光光照等级并广播。
     * @param id 粒子 ID
     * @param lightLevel 目标光照等级 (0-15)
     * @param playersInDimension 维度内的玩家列表
     */
    fun setLightLevel(id: UUID, lightLevel: Int, playersInDimension: Collection<ServerPlayer>) {
        val data = particles[id] ?: return
        data.setLightLevel(lightLevel)

        val payload = ParticleLightLevelPayload(id, data.lightLevel())
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
        private var lvl: Int? = null
        private var dur: Int = 0
        private var ease: EasingType = EasingType.LINEAR

        fun position(x: Double, y: Double, z: Double): UpdateBuilder {
            pos = Vec3(x, y, z); return this
        }
        fun position(v: Vec3): UpdateBuilder { pos = v; return this }
        fun color(c: Color): UpdateBuilder { col = c; return this }
        fun scale(s: Float): UpdateBuilder { scl = s; return this }
        fun lightLevel(level: Int): UpdateBuilder { lvl = level; return this }
        fun easing(e: EasingType, durationTicks: Int): UpdateBuilder { ease = e; dur = durationTicks; return this }
        fun duration(ticks: Int): UpdateBuilder { dur = ticks; return this }

        fun send(players: Collection<ServerPlayer>) {
            val data = particles[id] ?: return
            val p = pos ?: data.position()
            val c = col ?: data.color()
            val s = scl ?: data.scale()
            if (lvl != null) {
                setLightLevel(id, lvl!!, players)
            }
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
        sendToTracked(playersInDimension, listOf(id), payload)
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
        sendToTracked(playersInDimension, ids, payload)
        untrackParticles(ids)
    }

    /**
     * 每 tick 更新：推进生命周期、移除过期粒子。
     *
     * @param playersInDimension 维度内的玩家列表
     */
    fun tick(playersInDimension: Collection<ServerPlayer>) {
        // 实体锚点：位置每 tick 由锚点解析（不逐 tick 发包）。无玩家在线时没有关卡可查，
        // 保持上次位置即可——没有玩家也就没有渲染。
        val level = playersInDimension.firstOrNull()?.level()
        val it = particles.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val data = entry.value
            if (data.isAttached()) {
                val entity = resolveAttached(level, data)
                if (entity != null) {
                    data.setPosition(AttachMath.resolve(
                        entity.position(), entity.yRot, entity.xRot, data.attachOffset(), data.attachLocal()))
                }
            } else {
                data.stepMotion()
            }
            data.tick()
            if (data.isExpired()) {
                val groupId = data.groupId
                if (groupId != null) {
                    groups[groupId]?.removeMember(entry.key)
                }
                val payload = ParticleDestroyPayload.single(entry.key)
                sendToTracked(playersInDimension, listOf(entry.key), payload)
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

                sendToTracked(playersInDimension, batch.toList(), payload)
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
        val maxPerPlayer = ParticleDrawingConfig.SERVER.maxParticlesPerPlayer.get()

        for (player in players) {
            if (!ParticleVisibilityManager.isWithinViewDistance(player, position)) continue

            val trackedCount = playerParticles[player.uuid]?.size ?: 0
            if (trackedCount >= maxPerPlayer) continue

            PacketDistributor.sendToPlayer(player, payload)
            track(player.uuid, particleId)
        }
    }

    private fun recheckVisibility(players: Collection<ServerPlayer>) {
        if (players.isEmpty()) return
        val maxPerPlayer = ParticleDrawingConfig.SERVER.maxParticlesPerPlayer.get()

        for (player in players) {
            val tracked = playerParticles[player.uuid] ?: continue

            val toRemove = ArrayList<UUID>()
            for (particleId in tracked) {
                val data = particles[particleId]
                if (data == null || !ParticleVisibilityManager.isWithinViewDistance(player, data.position())) {
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
                    if (!ParticleVisibilityManager.isWithinViewDistance(player, data.position())) continue

                    PacketDistributor.sendToPlayer(player, spawnPayload(data))
                    track(player.uuid, particleId)
                    count++
                }
            }
        }
    }

    private fun spawnPayload(data: ParticleData): ParticleSpawnPayload {
        return ParticleSpawnPayload(
            data.id,
            data.position().x, data.position().y, data.position().z,
            data.color().r, data.color().g, data.color().b, data.color().a,
            data.scale(), data.lifetime(), data.groupId, data.glowing(), data.lightLevel(),
            data.visual(), data.lifeCurve(), null,   // 迟到补发不带上一次的插值端点：按当前位置出生
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
        for (player in players) {
            if (ParticleVisibilityManager.isWithinViewDistance(player, position)) {
                PacketDistributor.sendToPlayer(player, payload)
            }
        }
    }

    /**
     * 仅向已追踪了指定粒子的玩家发送数据包。
     *
     * 组变换与运动指令等数据包携带世界坐标（轴心），必须只发给实际拥有这些粒子的玩家，
     * 否则会向不可见这些粒子的玩家泄露坐标信息（无政府服务器可利用此获取他人位置）。
     */
    private fun sendToTracked(players: Collection<ServerPlayer>, particleIds: Collection<UUID>, payload: CustomPacketPayload) {
        val recipients = HashSet<UUID>()
        for (id in particleIds) {
            visibleTo[id]?.let { recipients.addAll(it) }
        }
        if (recipients.isEmpty()) return

        for (player in players) {
            if (player.uuid in recipients) {
                PacketDistributor.sendToPlayer(player, payload)
            }
        }
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
        @JvmStatic
        fun getOrCreate(dimensionId: UUID): ServerParticleEngine {
            return DIMENSION_ENGINES.computeIfAbsent(dimensionId) { ServerParticleEngine(it) }
        }

        /** @return 指定维度的引擎，不存在则返回 null */
        @JvmStatic
        fun get(dimensionId: UUID): ServerParticleEngine? = DIMENSION_ENGINES[dimensionId]

        /** 清除指定维度的引擎实例 */
        @JvmStatic
        fun clearDimension(dimensionId: UUID) {
            DIMENSION_ENGINES.remove(dimensionId)
        }
    }
}
