package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.core.network.ParticleExitWatchPayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 尺寸退场的完成账本。
 *
 * 服务端只负责登记与兜底，真正的“收束完成”由客户端按每个成员的实际缩放缓动回报；
 * 删除因此不按固定 `duration + 2` 估算。重定向会递增版本，旧回报与旧兜底都作废。
 *
 * 线程约定：主线程。
 */
object ServerExitCompletion {

    /** 客户端不可用/未回报时的兜底；比正常完成晚，给网络与暂停留余量。 */
    private const val FALLBACK_MARGIN_TICKS = 40

    private class Entry(val dimensionId: UUID) {
        var version: Int = 0
        var done: Boolean = false
        var removeAction: (() -> Unit)? = null

        /** 需要回报的玩家：只包含同时追踪全部成员的玩家。 */
        val pendingPlayers: MutableSet<UUID> = HashSet()
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /**
     * 登记一次退场观察。重复调用同一 [exitId] 视为重定向。
     *
     * @param ids 成员粒子；空列表立即完成
     * @param durationTicks 本次尺寸收束的最长时长，仅用于兜底
     */
    fun start(
        exitId: UUID,
        dimensionId: UUID,
        ids: List<UUID>,
        durationTicks: Int,
        players: Collection<ServerPlayer>,
        removeAction: (() -> Unit)?,
    ) {
        val entry = entries.computeIfAbsent(exitId) { Entry(dimensionId) }
        entry.version++
        entry.done = false
        entry.removeAction = removeAction
        entry.pendingPlayers.clear()
        val version = entry.version

        if (ids.isEmpty()) {
            finish(exitId, entry)
            return
        }

        val tracking = ServerParticleEngine.get(dimensionId)?.playersTrackingAll(ids) ?: emptySet()
        val recipients = players.filter { it.uuid in tracking }
        entry.pendingPlayers.addAll(recipients.map { it.uuid })

        if (recipients.isNotEmpty()) {
            val chunks = ids.chunked(ParticleExitWatchPayload.MAX_BATCH)
            for ((index, chunk) in chunks.withIndex()) {
                val payload = ParticleExitWatchPayload(
                    exitId,
                    version,
                    durationTicks.coerceAtLeast(0),
                    chunk,
                    index == chunks.lastIndex,
                )
                for (player in recipients) PacketDistributor.sendToPlayer(player, payload)
            }
        }
        scheduleFallback(exitId, entry, version, durationTicks)
    }

    /** 某个追踪玩家的完成回报；迟到、旧版本或非参与玩家直接丢弃。 */
    fun complete(exitId: UUID, version: Int, playerId: UUID) {
        val entry = entries[exitId] ?: return
        if (entry.done || entry.version != version) return
        if (!entry.pendingPlayers.remove(playerId)) return
        if (entry.pendingPlayers.isEmpty()) finish(exitId, entry)
    }

    /** 是否仍有未完成的退场观察（用于命名状态）。 */
    fun isPending(exitId: UUID): Boolean = entries[exitId]?.done == false

    fun cancel(exitId: UUID) {
        entries.remove(exitId)
    }

    fun clearDimension(dimensionId: UUID) {
        entries.keys.removeIf { entries[it]?.dimensionId == dimensionId }
    }

    fun clearAll() {
        entries.clear()
    }

    fun size(): Int = entries.size

    private fun scheduleFallback(exitId: UUID, entry: Entry, version: Int, durationTicks: Int) {
        val delay = durationTicks.coerceIn(0, Int.MAX_VALUE - FALLBACK_MARGIN_TICKS) +
            FALLBACK_MARGIN_TICKS
        AnimationScheduler.schedule(entry.dimensionId, delay) {
            val current = entries[exitId] ?: return@schedule
            if (current !== entry || current.done || current.version != version) return@schedule
            finish(exitId, current)
        }
    }

    private fun finish(exitId: UUID, entry: Entry) {
        entry.done = true
        entries.remove(exitId)
        entry.removeAction?.invoke()
    }
}
