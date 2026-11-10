package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 组程序的按玩家投递账本。
 *
 * 服务端只在玩家至少追踪到一个组成员时投递完整程序；程序里的成员清单保持全局顺序，
 * 客户端只为已经出生/可见的成员建状态，迟到成员由原生出生包补挂到同一时间轴。
 */
object ServerProgramDelivery {

    private class Delivery(
        val dimensionId: UUID,
        val members: List<UUID>,
        val sendFull: (ServerPlayer) -> Unit,
    )

    private val deliveries = ConcurrentHashMap<UUID, Delivery>()
    private val sentPlayers = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /** 首次全量程序：清掉已投递记录，后续按成员可见性重新投递。 */
    fun register(groupId: UUID, dimensionId: UUID, members: List<UUID>, sendFull: (ServerPlayer) -> Unit) {
        deliveries[groupId] = Delivery(dimensionId, members, sendFull)
        sentPlayers.remove(groupId)
    }

    /** 追加指令后更新完整快照；已经投递的玩家不重发全量。 */
    fun update(groupId: UUID, dimensionId: UUID, members: List<UUID>, sendFull: (ServerPlayer) -> Unit) {
        deliveries[groupId] = Delivery(dimensionId, members, sendFull)
    }

    fun isRegistered(groupId: UUID): Boolean = deliveries.containsKey(groupId)

    fun isSent(groupId: UUID, playerId: UUID): Boolean =
        sentPlayers[groupId]?.contains(playerId) == true

    /** 只给已经收到全量程序的玩家投递追加段。 */
    fun sendAppendToSent(groupId: UUID, players: Collection<ServerPlayer>, sendAppend: (ServerPlayer) -> Unit) {
        val sent = sentPlayers[groupId] ?: return
        for (player in players) {
            if (player.uuid in sent) sendAppend(player)
        }
    }

    fun sendTo(groupId: UUID, player: ServerPlayer): Boolean {
        val delivery = deliveries[groupId] ?: return false
        val sent = sentPlayers.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }
        if (!sent.add(player.uuid)) return false
        delivery.sendFull(player)
        return true
    }

    fun sendToAny(groupId: UUID, players: Collection<ServerPlayer>, candidates: Set<UUID>) {
        for (player in players) {
            if (player.uuid in candidates) sendTo(groupId, player)
        }
    }

    /**
     * 新出生/重新进入视距的成员：玩家还没收到程序时补发完整程序。
     *
     * [force] 用于「之前没有追踪该组任何成员」的场景：客户端可能已在切维度/清场时丢掉程序，
     * 即使服务端 sent 记录还在也重发一次。
     */
    fun onTracked(groupId: UUID, player: ServerPlayer, force: Boolean = false) {
        if (force) sentPlayers[groupId]?.remove(player.uuid)
        sendTo(groupId, player)
    }

    /** 玩家不再追踪该组任何成员：允许下次看到成员时重新投递。 */
    fun onUntracked(groupId: UUID, playerId: UUID) {
        sentPlayers[groupId]?.remove(playerId)
    }

    fun clear(groupId: UUID) {
        deliveries.remove(groupId)
        sentPlayers.remove(groupId)
    }

    fun clearPlayer(playerId: UUID) {
        for (sent in sentPlayers.values) sent.remove(playerId)
    }

    fun clearDimension(dimensionId: UUID) {
        deliveries.entries.removeIf { it.value.dimensionId == dimensionId }
        sentPlayers.keys.removeIf { groupId -> deliveries[groupId] == null }
    }

    fun clearAll() {
        deliveries.clear()
        sentPlayers.clear()
    }

    fun size(): Int = deliveries.size
}
