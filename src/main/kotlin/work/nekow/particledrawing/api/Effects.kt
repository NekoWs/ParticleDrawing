package work.nekow.particledrawing.api

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ServerEffectManager
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

// 特效播放门面（服务端），外部模组的主要入口：Effects.register 注册字节，Effects.play 播放并返回 EffectHandle 句柄。
object Effects {

    /** 注册 .pdrawc 特效字节（见 [EffectRegistry.register]）。 */
    @JvmStatic
    fun register(key: Identifier, data: ByteArray): Boolean = EffectRegistry.register(key, data)

    /** 播放一个已注册特效（世界坐标锚点等由 [anchor] 决定）。 */
    @JvmStatic
    @JvmOverloads
    fun play(key: Identifier, level: ServerLevel, anchor: Anchor, options: EffectOptions = EffectOptions()): EffectHandle {
        val id = ServerEffectManager.play(ParticleUtils.dimensionUUID(level), level.players(), key, anchor, options)
        return EffectHandle(id, level)
    }

    /** 便捷重载：从首个玩家所在维度推导 [ServerLevel]。 */
    @JvmStatic
    @JvmOverloads
    fun play(key: Identifier, players: Collection<ServerPlayer>, anchor: Anchor, options: EffectOptions = EffectOptions()): EffectHandle {
        require(players.isNotEmpty()) { "play 需要至少一个玩家" }
        val level: ServerLevel = players.first().level()
        val id = ServerEffectManager.play(players, key, anchor, options)
        return EffectHandle(id, level)
    }
}

/**
 * 服务端特效播放句柄：持有播放 ID 与服务端世界，供每 tick 更新锚点 / 控制播放。
 *
 * 控制方法（[seek]/[pause]/[resume]/[setSpeed]）仅在 [EffectOptions] 标记
 * [Authority.SERVER] 时广播；默认 [Authority.CLIENT_LOCAL] 下为 no-op（时钟由客户端各自推进）。
 */
class EffectHandle internal constructor(
    private val playbackId: UUID,
    private val level: ServerLevel,
) {
    /** 更新可移动锚点（每 tick 调用；随后由服务端批量下发）。 */
    fun updateAnchor(pos: Vec3, velocity: Vec3): EffectHandle {
        ServerEffectManager.updateAnchor(playbackId, pos, velocity)
        return this
    }

    fun stop(): EffectHandle {
        ServerEffectManager.stop(playbackId, level.players())
        return this
    }

    /** 跳转到时间轴毫秒位置（未设 `authority(SERVER)` 时只影响本地）。 */
    fun seek(ms: Double): EffectHandle {
        ServerEffectManager.seek(playbackId, level.players(), ms)
        return this
    }

    fun pause(): EffectHandle {
        ServerEffectManager.pause(playbackId, level.players())
        return this
    }

    fun resume(): EffectHandle {
        ServerEffectManager.resume(playbackId, level.players())
        return this
    }

    fun setSpeed(speed: Double): EffectHandle {
        ServerEffectManager.setSpeed(playbackId, level.players(), speed)
        return this
    }

    fun setVariable(name: String, value: String): EffectHandle {
        ServerEffectManager.updateVariable(playbackId, name, value, level.players())
        return this
    }

    fun isActive(): Boolean = ServerEffectManager.isActive(playbackId)

    fun id(): UUID = playbackId
}