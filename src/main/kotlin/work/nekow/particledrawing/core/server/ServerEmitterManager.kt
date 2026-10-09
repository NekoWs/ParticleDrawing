package work.nekow.particledrawing.core.server

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.EmitMode
import work.nekow.particledrawing.core.network.EmitterParams
import work.nekow.particledrawing.core.network.EmitterSpawnPayload
import work.nekow.particledrawing.core.network.EmitterStopPayload
import work.nekow.particledrawing.core.network.EmitterUpdatePayload
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端发射器登记表：发射行为全部在客户端发生，这里只在声明时登记、变更时更新，
 * 并记录哪些玩家已收到，供玩家后进服或走进范围时补发。
 *
 * 线程约定：与服务端粒子引擎一致，只在主线程调用。
 */
object ServerEmitterManager {

    private val LOGGER = org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing")

    /** 补发扫描间隔（tick）：后进服或走进范围的玩家在该周期内收到声明。 */
    private const val CATCH_UP_INTERVAL = 20

    /** 同时活跃的发射器上限，用于限制声明泄漏。 */
    private const val MAX_ACTIVE_EMITTERS = 512

    private class Record(
        val dimensionId: UUID,
        val emitterId: UUID,
        var anchor: Anchor,
        var params: EmitterParams,
    ) {
        /** 已收到声明的玩家；未收到的不发更新/停止，也顺带不泄露坐标。 */
        val sent: MutableSet<UUID> = HashSet()

        fun declaration(): EmitterSpawnPayload = EmitterSpawnPayload(emitterId, anchor, params)

        fun anchorUpdate(): EmitterUpdatePayload = EmitterUpdatePayload(emitterId, anchor = anchor)

        fun cadenceUpdate(): EmitterUpdatePayload = EmitterUpdatePayload(
            emitterId,
            cadence = EmitterUpdatePayload.EmitterCadence(params.mode, params.spacing, params.intervalMs),
        )

        fun paramsUpdate(): EmitterUpdatePayload = EmitterUpdatePayload(emitterId, params = params)
    }

    private val records = ConcurrentHashMap<UUID, Record>()
    private var tickCounter = 0

    /** 范围判断用的锚点位置：实体锚点由客户端解析，服务端只知道偏移，按偏移近似。 */
    private fun anchorPos(anchor: Anchor): Vec3 = when (anchor) {
        is Anchor.Fixed -> anchor.pos
        is Anchor.Entity -> anchor.offset
        is Anchor.Movable -> anchor.pos
    }

    /**
     * 声明一个发射器：向维度内可见玩家下发，返回发射器 id；已达数量上限时返回 null。
     *
     * 上限用于限制声明泄漏：发射器不消失（服务端一直留着登记、客户端一直发），
     * 漏掉 `stop()` 就会越攒越多。到顶时打 ERROR 并拒绝登记，`EmitterHandle.isActive()` 会返回 false。
     */
    fun start(dimensionId: UUID, anchor: Anchor, params: EmitterParams,
              players: Collection<ServerPlayer>): UUID? {
        if (records.size >= MAX_ACTIVE_EMITTERS) {
            LOGGER.error(
                "发射器数量已达上限（{}），声明被拒绝：发射器不会自动回收，用完要 stop()",
                MAX_ACTIVE_EMITTERS,
            )
            return null
        }
        val emitterId = UUID.randomUUID()
        val record = Record(dimensionId, emitterId, anchor, params)
        records[emitterId] = record
        for (player in players) {
            if (!isWithinRange(player, anchor)) continue
            record.sent.add(player.uuid)
            PacketDistributor.sendToPlayer(player, record.declaration())
        }
        return emitterId
    }

    /**
     * 锚点变更：已收到声明的玩家收只带锚点的小包，这一拍刚走进范围的玩家直接收整份声明。
     */
    fun updateAnchor(emitterId: UUID, anchor: Anchor, players: Collection<ServerPlayer>) {
        val record = records[emitterId] ?: return
        record.anchor = anchor
        for (player in players) {
            if (!isWithinRange(player, anchor)) continue
            if (record.sent.add(player.uuid)) {
                PacketDistributor.sendToPlayer(player, record.declaration())
            } else {
                PacketDistributor.sendToPlayer(player, record.anchorUpdate())
            }
        }
    }

    /** 发射口径变更（间距/间隔）；已经收到过声明的玩家收一小包更新。 */
    fun updateCadence(emitterId: UUID, mode: EmitMode, spacing: Double, intervalMs: Int,
                      players: Collection<ServerPlayer>) {
        val record = records[emitterId] ?: return
        record.params = record.params.copy(mode = mode, spacing = spacing, intervalMs = intervalMs)
        val payload = record.cadenceUpdate()
        for (player in players) {
            if (player.uuid in record.sent) PacketDistributor.sendToPlayer(player, payload)
        }
    }

    /**
     * 静态参数整份变更（寿命 / 缩放 / 曲线 / 外观 / 抖动等）：已收到声明的玩家收一份参数包，
     * 客户端整份替换。已生成的粒子不受影响。
     */
    fun updateParams(emitterId: UUID, params: EmitterParams, players: Collection<ServerPlayer>) {
        val record = records[emitterId] ?: return
        record.params = params
        val payload = record.paramsUpdate()
        for (player in players) {
            if (player.uuid in record.sent) PacketDistributor.sendToPlayer(player, payload)
        }
    }

    /** 停掉发射器：已生成的粒子不受影响，各自走完寿命。 */
    fun stop(emitterId: UUID, players: Collection<ServerPlayer>) {
        val record = records.remove(emitterId) ?: return
        val payload = EmitterStopPayload(record.emitterId)
        for (player in players) {
            if (player.uuid in record.sent) PacketDistributor.sendToPlayer(player, payload)
        }
    }

    fun isActive(emitterId: UUID): Boolean = records.containsKey(emitterId)

    /** 玩家断线：清掉他的「已收到」记录，重进时按当前维度补发。 */
    fun forgetPlayer(playerUuid: UUID) {
        for (record in records.values) record.sent.remove(playerUuid)
    }

    fun clearDimension(dimensionId: UUID, players: Collection<ServerPlayer>) {
        val it = records.entries.iterator()
        while (it.hasNext()) {
            val (_, record) = it.next()
            if (record.dimensionId != dimensionId) continue
            val payload = EmitterStopPayload(record.emitterId)
            for (player in players) {
                if (player.uuid in record.sent) PacketDistributor.sendToPlayer(player, payload)
            }
            it.remove()
        }
    }

    /**
     * 玩家到达新维度（登录/切维度/重生）后补发该维度里范围内、他还没收到的声明。
     * 客户端换维度会重建渲染层，旧的发射器随之消失，需要重发。
     */
    fun syncToPlayer(player: ServerPlayer) {
        val level = player.level()
        val dim = ParticleUtils.dimensionUUID(level)
        for (record in records.values) {
            if (record.dimensionId != dim) continue
            if (!isWithinRange(player, record.anchor)) continue
            if (record.sent.add(player.uuid)) {
                PacketDistributor.sendToPlayer(player, record.declaration())
            }
        }
    }

    /**
     * 每服务端 tick：定期给已进入范围但还没收到声明的玩家补发。
     * 时间口径的发射器不会有锚点更新，只能靠这个扫描补发。
     */
    fun tick(server: MinecraftServer) {
        if (records.isEmpty()) return
        if (++tickCounter % CATCH_UP_INTERVAL != 0) return
        for (level in server.allLevels) {
            val dim = ParticleUtils.dimensionUUID(level)
            for (record in records.values) {
                if (record.dimensionId != dim) continue
                for (player in level.players()) {
                    if (player.uuid in record.sent) continue
                    if (!isWithinRange(player, record.anchor)) continue
                    record.sent.add(player.uuid)
                    PacketDistributor.sendToPlayer(player, record.declaration())
                }
            }
        }
    }

    private fun isWithinRange(player: ServerPlayer, anchor: Anchor): Boolean =
        ParticleVisibilityManager.isWithinViewDistance(player, anchorPos(anchor))
}
