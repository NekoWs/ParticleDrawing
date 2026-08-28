package work.nekow.particledrawing.animation

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.core.network.PlayAnimationPayload
import work.nekow.particledrawing.core.network.StopAnimationPayload
import work.nekow.particledrawing.core.network.VariableUpdatePayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端动画管理器（权威播放/停止/变量）。
 * 只负责向客户端下发 .pdrawc 动画定义、停止命令与变量更新，粒子播放与渲染全部在客户端本地进行。
 *
 * 外部模组典型用法：
 * ```kotlin
 * val animId = ServerAnimationManager.playByName(dim, players, "magic_circle", origin) ?: return
 * ServerAnimationManager.updateVariable(animId, "rad", "3 + sin(t)", players)
 * ServerAnimationManager.stop(animId, players)
 * ```
 */
@Suppress("unused")
object ServerAnimationManager {

    private class Playback(
        val animationId: UUID,
        val dimensionId: UUID,
        val playerIds: Set<UUID>,
    )

    private val playbacks = ConcurrentHashMap<UUID, Playback>()

    /** 启动播放：向玩家下发 .pdrawc 字节与原点，返回动画 ID。 */
    @JvmStatic
    fun play(dimensionId: UUID, players: Collection<ServerPlayer>, data: ByteArray, origin: Vec3): UUID {
        val id = UUID.randomUUID()
        val payload = PlayAnimationPayload(id, origin.x, origin.y, origin.z, data)
        val ids = HashSet<UUID>()
        for (player in players) {
            PacketDistributor.sendToPlayer(player, payload)
            ids.add(player.uuid)
        }
        playbacks[id] = Playback(id, dimensionId, ids)
        return id
    }

    /**
     * 按名称播放 `<gameDir>/animations/<name>.pdrawc`（一行式入口，读取时做服务端验签）。
     * @param name 动画名（不含 .pdrawc 后缀）
     * @return 动画 ID；找不到文件或验签失败时为 null
     */
    @JvmStatic
    fun playByName(
        dimensionId: UUID, players: Collection<ServerPlayer>,
        name: String, origin: Vec3
    ): UUID? {
        val data = AnimationLoader.load(name) ?: return null
        return play(dimensionId, players, data, origin)
    }

    /** 停止指定维度全部播放。 */
    @JvmStatic
    fun stopAll(dimensionId: UUID, players: Collection<ServerPlayer>) {
        val ids = playbacks.values.filter { it.dimensionId == dimensionId }.map { it.animationId }
        if (ids.isEmpty()) return
        val payload = StopAnimationPayload(null)
        for (player in players) PacketDistributor.sendToPlayer(player, payload)
        for (id in ids) playbacks.remove(id)
    }

    /**
     * 停止单次播放：只通知该次播放覆盖到的玩家并清理记录。
     * @return 是否存在该次播放
     */
    @JvmStatic
    fun stop(animationId: UUID, players: Collection<ServerPlayer>): Boolean {
        val pb = playbacks.remove(animationId) ?: return false
        val payload = StopAnimationPayload(animationId)
        for (player in players) {
            if (player.uuid in pb.playerIds) PacketDistributor.sendToPlayer(player, payload)
        }
        return true
    }

    /** 更新某次播放的变量。 */
    @JvmStatic
    fun updateVariable(animationId: UUID, name: String, value: String, players: Collection<ServerPlayer>) {
        val pb = playbacks[animationId] ?: return
        val payload = VariableUpdatePayload(animationId, name, value)
        for (player in players) {
            if (player.uuid in pb.playerIds) PacketDistributor.sendToPlayer(player, payload)
        }
    }

    /** 更新某维度全部播放的变量（广播到所有正在播放的实例）。 */
    @JvmStatic
    fun updateVariableForDimension(dimensionId: UUID, name: String, value: String, players: Collection<ServerPlayer>) {
        val ids = playbacks.values.filter { it.dimensionId == dimensionId }.map { it.animationId }
        for (id in ids) updateVariable(id, name, value, players)
    }

    /** 该次播放是否仍在进行。 */
    @JvmStatic
    fun isActive(animationId: UUID): Boolean = playbacks.containsKey(animationId)

    /** 列出指定维度的活跃播放 ID 快照（无序）。 */
    @JvmStatic
    fun activePlaybacks(dimensionId: UUID): List<UUID> =
        playbacks.values.filter { it.dimensionId == dimensionId }.map { it.animationId }

    /** 列出全部维度的活跃播放 ID 快照（无序）。 */
    @JvmStatic
    fun activePlaybacksAll(): List<UUID> = playbacks.keys.toList()

    /** 该次播放覆盖的玩家 ID 集合快照；不存在时为空集。 */
    @JvmStatic
    fun playbackPlayers(animationId: UUID): Set<UUID> =
        playbacks[animationId]?.playerIds ?: emptySet()
}
