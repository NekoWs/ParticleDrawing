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
 * 只负责向客户端下发 .pdraw 动画定义、停止命令与变量更新，粒子播放与渲染全部在客户端本地进行。
 */
object ServerAnimationManager {

    private class Playback(
        val animationId: UUID,
        val dimensionId: UUID,
        val playerIds: Set<UUID>,
    )

    private val playbacks = ConcurrentHashMap<UUID, Playback>()

    /** 启动播放：向玩家下发 .pdraw JSON 与原点，返回动画 ID。 */
    @JvmStatic
    fun play(dimensionId: UUID, players: Collection<ServerPlayer>, json: String, origin: Vec3): UUID {
        val id = UUID.randomUUID()
        val payload = PlayAnimationPayload(id, origin.x, origin.y, origin.z, json)
        val ids = HashSet<UUID>()
        for (player in players) {
            PacketDistributor.sendToPlayer(player, payload)
            ids.add(player.uuid)
        }
        playbacks[id] = Playback(id, dimensionId, ids)
        return id
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
}
