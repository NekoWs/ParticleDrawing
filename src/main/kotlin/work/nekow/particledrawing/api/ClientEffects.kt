package work.nekow.particledrawing.api

import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.core.client.ClientAnimationManager
import java.util.UUID

// 纯客户端本地特效播放入口（不经服务端、不占同步）。字节从客户端本地 EffectRegistry 读取；仅客户端代码调用。
object ClientEffects {

    /** 播放一个客户端本地注册的特效；未注册/解析失败返回 null。 */
    @JvmStatic
    fun play(key: Identifier, anchor: Anchor, options: EffectOptions): ClientEffectHandle? {
        val data = EffectRegistry.dataFor(key) ?: return null
        val animation = try {
            AnimationLoader.parse(data)
        } catch (_: Exception) {
            return null
        }
        val id = UUID.randomUUID()
        ClientAnimationManager.playLocal(id, animation, anchor, options)
        return ClientEffectHandle(id)
    }
}

/** 客户端本地播放句柄：时钟控制始终本地生效；仅从客户端代码调用。 */
class ClientEffectHandle internal constructor(private val playbackId: UUID) {

    fun updateAnchor(pos: Vec3, velocity: Vec3): ClientEffectHandle {
        ClientAnimationManager.updateAnchor(playbackId, pos, velocity)
        return this
    }

    fun stop(): ClientEffectHandle {
        ClientAnimationManager.stop(playbackId)
        return this
    }

    /** 跳转到时间轴毫秒位置（纯本地视觉）。 */
    fun seek(ms: Double): ClientEffectHandle {
        ClientAnimationManager.seekLocal(playbackId, ms)
        return this
    }

    fun pause(): ClientEffectHandle {
        ClientAnimationManager.pauseLocal(playbackId)
        return this
    }

    fun resume(): ClientEffectHandle {
        ClientAnimationManager.resumeLocal(playbackId)
        return this
    }

    fun setSpeed(speed: Double): ClientEffectHandle {
        ClientAnimationManager.setSpeedLocal(playbackId, speed)
        return this
    }

    fun setVariable(name: String, value: String): ClientEffectHandle {
        ClientAnimationManager.updateVariable(playbackId, name, value)
        return this
    }

    fun isActive(): Boolean = ClientAnimationManager.isActive(playbackId)
}