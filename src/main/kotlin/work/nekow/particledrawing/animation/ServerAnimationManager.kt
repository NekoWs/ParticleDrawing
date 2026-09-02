package work.nekow.particledrawing.animation

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.core.network.PlayAnimationDataPayload
import work.nekow.particledrawing.core.network.PlayAnimationPayload
import work.nekow.particledrawing.core.network.StopAnimationPayload
import work.nekow.particledrawing.core.network.VariableUpdatePayload
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端动画管理器（权威播放/停止/变量）。
 * 只负责向客户端下发 .pdrawc 动画定义、停止命令与变量更新，粒子播放与渲染全部在客户端本地进行。
 *
 * 外部模组典型用法：
 * ```kotlin
 * val animId = ServerAnimationManager.playByName(dim, players, "magic_circle", origin) ?: return
 * ServerAnimationManager.updateVariable(animId, "rad", "4", players)
 * ServerAnimationManager.stop(animId, players)
 * ```
 */
@Suppress("unused")
object ServerAnimationManager {

    private class Playback(
        val animationId: UUID,
        val dimensionId: UUID,
        val playerIds: Set<UUID>,
        // 重发所需：播放原点与动画定义（维度切换/重生/重连后把播放重新下发给玩家）。
        // 文件播放路径持有 .pdrawc 原始字节；代码生成路径直接持有 ParticleAnimation。
        val origin: Vec3,
        val data: ByteArray?,
        val animation: ParticleAnimation?,
        // 服务端权威进度：起始 gameTime 与时间轴参数（进度 = wrap/clamp(gameTime - startGameTick)）
        val startGameTick: Long,
        val maxTick: Int,
        val loop: Boolean,
    )

    private val playbacks = ConcurrentHashMap<UUID, Playback>()

    /** 启动播放：向玩家下发 .pdrawc 字节、原点与服务端进度起点，返回动画 ID。 */
    @JvmStatic
    fun play(dimensionId: UUID, players: Collection<ServerPlayer>, data: ByteArray, origin: Vec3): UUID {
        val id = UUID.randomUUID()
        // 服务端解析时间轴参数（loop/长度）用于计算进度；解析失败仍照旧下发（客户端各自拒绝播放）
        val anim = try { AnimationLoader.parse(data) } catch (_: Exception) { null }
        val startGameTick = players.firstOrNull()?.level()?.gameTime ?: 0L
        val payload = PlayAnimationPayload(id, origin.x, origin.y, origin.z, startGameTick, data)
        val ids = HashSet<UUID>()
        for (player in players) {
            PacketDistributor.sendToPlayer(player, payload)
            ids.add(player.uuid)
        }
        playbacks[id] = Playback(id, dimensionId, ids, origin, data, null, startGameTick, anim?.timelineLength() ?: 0, anim?.loop ?: false)
        return id
    }

    /**
     * 播放一个代码生成的 [ParticleAnimation]（结构化载荷直发，不验签）。
     * @return 动画 ID；可随后用 [stop] / [updateVariable] 操作
     */
    @JvmStatic
    fun play(animation: ParticleAnimation, dimensionId: UUID, players: Collection<ServerPlayer>, origin: Vec3): UUID {
        val id = UUID.randomUUID()
        val startGameTick = players.firstOrNull()?.level()?.gameTime ?: 0L
        val payload = PlayAnimationDataPayload(id, origin.x, origin.y, origin.z, startGameTick, animation)
        val ids = HashSet<UUID>()
        for (player in players) {
            PacketDistributor.sendToPlayer(player, payload)
            ids.add(player.uuid)
        }
        playbacks[id] = Playback(id, dimensionId, ids, origin, null, animation, startGameTick, animation.timelineLength(), animation.loop)
        return id
    }

    /** [play] 的便捷重载：从首个玩家所在的维度推导 dimensionId。 */
    @JvmStatic
    fun play(animation: ParticleAnimation, players: Collection<ServerPlayer>, origin: Vec3): UUID {
        require(players.isNotEmpty()) { "play 需要至少一个玩家" }
        val level: ServerLevel = players.first().level()
        return play(animation, ParticleUtils.dimensionUUID(level), players, origin)
    }

    /**
     * 把玩家所在维度内、且覆盖到该玩家的全部活跃播放重新下发。
     * 客户端在切换维度/重生/重连时会重建 ClientLevel 与原版 ParticleEngine，
     * 本地播放的桥接粒子随之销毁；此方法让玩家回来时重新收到播放包、重建粒子，
     * 并按服务端权威进度（同一 startGameTick + gameTime 时钟）从当前帧继续，
     * 与仍在观看的其他玩家画面一致。已播完的非循环播放不再下发。
     * 三个触发点（维度切换 / 重生 / 登录）各只触发一次，不会重复下发。
     */
    @JvmStatic
    fun syncPlaybacksToPlayer(player: ServerPlayer) {
        val level = player.level()
        val dim = ParticleUtils.dimensionUUID(level)
        val gameTime = level.gameTime
        for (pb in playbacks.values) {
            if (pb.dimensionId != dim || player.uuid !in pb.playerIds) continue
            if (AnimationProgress.isFinished(gameTime - pb.startGameTick, pb.maxTick, pb.loop)) continue
            val anim = pb.animation
            if (anim != null) {
                PacketDistributor.sendToPlayer(
                    player,
                    PlayAnimationDataPayload(pb.animationId, pb.origin.x, pb.origin.y, pb.origin.z, pb.startGameTick, anim)
                )
            } else if (pb.data != null) {
                PacketDistributor.sendToPlayer(
                    player,
                    PlayAnimationPayload(pb.animationId, pb.origin.x, pb.origin.y, pb.origin.z, pb.startGameTick, pb.data)
                )
            }
        }
    }

    /** 清理指定维度已播完（非循环）的播放记录（服务端每 tick 调用）。 */
    @JvmStatic
    fun removeFinished(dimensionId: UUID, gameTime: Long) {
        playbacks.entries.removeIf { (_, pb) ->
            pb.dimensionId == dimensionId && AnimationProgress.isFinished(gameTime - pb.startGameTick, pb.maxTick, pb.loop)
        }
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
        sendToPlaybackPlayers(pb, players, StopAnimationPayload(animationId))
        return true
    }

    /** 更新某次播放的变量。 */
    @JvmStatic
    fun updateVariable(animationId: UUID, name: String, value: String, players: Collection<ServerPlayer>) {
        val pb = playbacks[animationId] ?: return
        sendToPlaybackPlayers(pb, players, VariableUpdatePayload(animationId, name, value))
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

    private fun sendToPlaybackPlayers(pb: Playback, players: Collection<ServerPlayer>, payload: CustomPacketPayload) {
        for (player in players) {
            if (player.uuid in pb.playerIds) PacketDistributor.sendToPlayer(player, payload)
        }
    }
}
