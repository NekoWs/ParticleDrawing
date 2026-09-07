package work.nekow.particledrawing.animation

import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.Authority
import work.nekow.particledrawing.api.EffectOptions
import work.nekow.particledrawing.api.EffectRegistry
import work.nekow.particledrawing.api.Orient
import work.nekow.particledrawing.core.network.AnchorUpdateBatchPayload
import work.nekow.particledrawing.core.network.ClockSyncPayload
import work.nekow.particledrawing.core.network.PlayEffectPayload
import work.nekow.particledrawing.core.network.StopAnimationPayload
import work.nekow.particledrawing.core.network.VariableUpdatePayload
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// 服务端特效播放管理器（按 key 播放 .pdrawc + 可移动锚点 + 播放时钟）。
// 与 ServerAnimationManager 并存：资源注册表按 key 播放；可移动锚点每 tick 批量下发一次；时钟支持 seek/暂停/变速。
object ServerEffectManager {

    private class Playback(
        val playbackId: UUID,
        val dimensionId: UUID,
        val playerIds: Set<UUID>,
        val key: Identifier,
        var anchor: Anchor,
        val options: EffectOptions,
        val maxTick: Int,
        val loop: Boolean,
        val startGameTick: Long,
        val clock: PlaybackClock,
    )

    private val playbacks = ConcurrentHashMap<UUID, Playback>()
    private val pendingAnchors = ConcurrentHashMap<UUID, AnchorUpdateBatchPayload.AnchorUpdate>()

    /**
     * 播放一个已注册特效。
     * @return 播放 ID；未注册/解析失败抛 [IllegalArgumentException]
     */
    @JvmStatic
    fun play(
        dimensionId: UUID,
        players: Collection<ServerPlayer>,
        key: Identifier,
        anchor: Anchor,
        options: EffectOptions,
    ): UUID {
        val data = EffectRegistry.dataFor(key)
            ?: throw IllegalArgumentException("特效未注册: $key")
        val anim = try {
            PdrawcReader.parse(data)
        } catch (e: Exception) {
            throw IllegalArgumentException("特效解析失败: $key", e)
        }
        val id = UUID.randomUUID()
        val loop = options.loop() ?: anim.loop
        val maxTick = anim.timelineLength()
        val startGameTick = players.firstOrNull()?.level()?.gameTime ?: 0L
        val clock = PlaybackClock(options.startTick(), playing = true, speed = options.speed())

        val payload = PlayEffectPayload(id, key, anchor, options, startGameTick)
        val ids = HashSet<UUID>()
        for (player in players) {
            PacketDistributor.sendToPlayer(player, payload)
            ids.add(player.uuid)
        }
        playbacks[id] = Playback(id, dimensionId, ids, key, anchor, options, maxTick, loop, startGameTick, clock)
        return id
    }

    /** 便捷重载：从首个玩家所在维度推导 dimensionId。 */
    @JvmStatic
    fun play(players: Collection<ServerPlayer>, key: Identifier, anchor: Anchor, options: EffectOptions): UUID {
        require(players.isNotEmpty()) { "play 需要至少一个玩家" }
        val level: ServerLevel = players.first().level()
        return play(ParticleUtils.dimensionUUID(level), players, key, anchor, options)
    }

    /**
     * 更新可移动锚点（魔法模组每 tick 调用一次）。调用即覆盖最新值，
     * 由 [flushAnchorUpdates] 在服务端 tick 末批量下发。
     */
    @JvmStatic
    fun updateAnchor(playbackId: UUID, pos: Vec3, velocity: Vec3) {
        val pb = playbacks[playbackId] ?: return
        val orient = (pb.anchor as? Anchor.Movable)?.orient ?: Orient.VELOCITY
        pb.anchor = Anchor.Movable(pos, velocity, orient)
        pendingAnchors[playbackId] = AnchorUpdateBatchPayload.AnchorUpdate(
            playbackId, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z
        )
    }

    /** 每服务端 tick 末调用：把本 tick 累积的锚点更新按维度批量下发。 */
    @JvmStatic
    fun flushAnchorUpdates(server: MinecraftServer) {
        if (pendingAnchors.isEmpty()) return
        val updates = pendingAnchors.values.toList()
        pendingAnchors.clear()

        val levelByDim = HashMap<UUID, ServerLevel>()
        for (level in server.allLevels) levelByDim[ParticleUtils.dimensionUUID(level)] = level

        val byDim = LinkedHashMap<UUID, MutableList<AnchorUpdateBatchPayload.AnchorUpdate>>()
        for (u in updates) {
            val pb = playbacks[u.playbackId] ?: continue
            byDim.getOrPut(pb.dimensionId) { ArrayList() }.add(u)
        }
        for ((dim, list) in byDim) {
            val level = levelByDim[dim] ?: continue
            val payload = AnchorUpdateBatchPayload(list)
            val targets = HashSet<ServerPlayer>()
            for (u in list) {
                val pb = playbacks[u.playbackId] ?: continue
                for (p in level.players()) if (p.uuid in pb.playerIds) targets.add(p)
            }
            for (p in targets) PacketDistributor.sendToPlayer(p, payload)
        }
    }

    /** 每服务端 tick 推进参考时钟并清理已播完的非循环播放。 */
    @JvmStatic
    fun tickClocks() {
        val toRemove = ArrayList<UUID>()
        for (pb in playbacks.values) {
            pb.clock.advance()
            if (!pb.loop && pb.maxTick > 0 && pb.clock.position >= pb.maxTick) toRemove.add(pb.playbackId)
        }
        for (id in toRemove) {
            playbacks.remove(id)
            pendingAnchors.remove(id)
        }
    }

    // —— 播放控制（服务端权威时才广播） ——

    @JvmStatic
    fun seek(playbackId: UUID, players: Collection<ServerPlayer>, tick: Double): Boolean {
        val pb = playbacks[playbackId] ?: return false
        if (pb.options.authority() != Authority.SERVER) return false
        pb.clock.position = tick
        sendClock(pb, players)
        return true
    }

    @JvmStatic
    fun pause(playbackId: UUID, players: Collection<ServerPlayer>): Boolean {
        val pb = playbacks[playbackId] ?: return false
        if (pb.options.authority() != Authority.SERVER) return false
        pb.clock.playing = false
        sendClock(pb, players)
        return true
    }

    @JvmStatic
    fun resume(playbackId: UUID, players: Collection<ServerPlayer>): Boolean {
        val pb = playbacks[playbackId] ?: return false
        if (pb.options.authority() != Authority.SERVER) return false
        pb.clock.playing = true
        sendClock(pb, players)
        return true
    }

    @JvmStatic
    fun setSpeed(playbackId: UUID, players: Collection<ServerPlayer>, speed: Double): Boolean {
        val pb = playbacks[playbackId] ?: return false
        if (pb.options.authority() != Authority.SERVER) return false
        pb.clock.speed = speed
        sendClock(pb, players)
        return true
    }

    private fun sendClock(pb: Playback, players: Collection<ServerPlayer>) {
        val payload = ClockSyncPayload(pb.playbackId, pb.clock.position, pb.clock.playing, pb.clock.speed)
        for (p in players) {
            if (p.uuid in pb.playerIds) PacketDistributor.sendToPlayer(p, payload)
        }
    }

    // —— 停止 / 变量 / 查询 ——

    @JvmStatic
    fun stop(playbackId: UUID, players: Collection<ServerPlayer>): Boolean {
        val pb = playbacks.remove(playbackId) ?: return false
        pendingAnchors.remove(playbackId)
        for (p in players) {
            if (p.uuid in pb.playerIds) PacketDistributor.sendToPlayer(p, StopAnimationPayload(playbackId))
        }
        return true
    }

    @JvmStatic
    fun stopAll(dimensionId: UUID, players: Collection<ServerPlayer>) {
        val ids = playbacks.values.filter { it.dimensionId == dimensionId }.map { it.playbackId }
        if (ids.isEmpty()) return
        for (p in players) PacketDistributor.sendToPlayer(p, StopAnimationPayload(null))
        for (id in ids) {
            playbacks.remove(id)
            pendingAnchors.remove(id)
        }
    }

    /** 更新某次播放的变量（复用旧 VariableUpdatePayload，客户端同一链路处理）。 */
    @JvmStatic
    fun updateVariable(playbackId: UUID, name: String, value: String, players: Collection<ServerPlayer>) {
        val pb = playbacks[playbackId] ?: return
        val payload = VariableUpdatePayload(playbackId, name, value)
        for (p in players) {
            if (p.uuid in pb.playerIds) PacketDistributor.sendToPlayer(p, payload)
        }
    }

    @JvmStatic
    fun isActive(playbackId: UUID): Boolean = playbacks.containsKey(playbackId)

    /**
     * 维度切换/重生/登录后重发该玩家的活跃播放（与 [ServerAnimationManager.syncPlaybacksToPlayer] 同理）。
     */
    @JvmStatic
    fun syncPlaybacksToPlayer(player: ServerPlayer) {
        val level = player.level()
        val dim = ParticleUtils.dimensionUUID(level)
        for (pb in playbacks.values) {
            if (pb.dimensionId != dim || player.uuid !in pb.playerIds) continue
            if (!pb.loop && pb.maxTick > 0 && pb.clock.position >= pb.maxTick) continue
            PacketDistributor.sendToPlayer(
                player,
                PlayEffectPayload(pb.playbackId, pb.key, pb.anchor, pb.options, pb.startGameTick)
            )
        }
    }
}