package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.api.ParticleStyle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 客户端动画管理器：本地播放服务端下发的 .pdraw 动画，每客户端 tick 推进并同步渲染。
 */
object ClientAnimationManager {

    private class Entry(
        val player: ClientAnimationPlayer,
        val particleUuids: MutableMap<String, UUID>,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** 一次播放的调试信息快照。 */
    data class DebugInfo(
        val animId: UUID,
        val particleCount: Int,
        val currentTick: Int,
        val maxTick: Int,
        val frameCount: Long,
        val lastAdvanceMillis: Double,
        val avgAdvanceMillis: Double,
    )

    /** 收集当前所有播放中动画的调试信息。 */
    @JvmStatic
    fun debugInfo(): List<DebugInfo> = entries.map { (id, e) ->
        DebugInfo(
            animId = id,
            particleCount = e.player.particleCount,
            currentTick = e.player.currentTickValue,
            maxTick = e.player.maxTickValue,
            frameCount = e.player.frameCount,
            lastAdvanceMillis = e.player.lastAdvanceNanos / 1_000_000.0,
            avgAdvanceMillis = e.player.avgAdvanceNanos / 1_000_000.0,
        )
    }

    /** 当前播放中的动画数量。 */
    @JvmStatic
    fun activeAnimationCount(): Int = entries.size

    /** 开始本地播放一个动画。 */
    @JvmStatic
    fun play(animationId: UUID, json: String, origin: Vec3) {
        val animation = try {
            AnimationLoader.parse(json)
        } catch (_: Exception) {
            return
        }
        // 播放前预加载动画引用的全部贴图（纹理必须在粒子渲染前注册，见 TextureCache）
        for (texName in animation.textures) ensureTextureLoaded(texName)

        val player = ClientAnimationPlayer(animation, origin)
        val uuids = HashMap<String, UUID>()
        for (state in player.currentStates()) {
            val uuid = UUID.randomUUID()
            uuids[state.id] = uuid
            ClientParticleEngine.instance()?.spawnParticle(
                uuid, ParticleStyle.DOT, state.pos.x, state.pos.y, state.pos.z,
                state.color.r, state.color.g, state.color.b, state.color.a,
                state.scale[0], -1, null, state.glowing, state.lightLevel, state.uv
            )
        }
        entries[animationId] = Entry(player, uuids)
    }

    /** 确保一张贴图已加载并注册（主线程调用）。失败静默忽略，粒子回退为纯色方块。 */
    private fun ensureTextureLoaded(texName: String) {
        try {
            TextureCache.load(texName)
        } catch (_: Exception) {
            // 忽略：贴图缺失/解码失败时粒子退化为无贴图渲染
        }
    }

    /** 每客户端 tick 推进所有动画并同步渲染。 */
    @JvmStatic
    fun tick() {
        val toStop = mutableListOf<UUID>()
        for ((animId, entry) in entries) {
            if (entry.player.tick()) {
                // 静态动画（粒子状态恒定）跳过每刻的渲染同步，避免 5w 粒子无谓的逐粒子写入
                if (!entry.player.isStatic()) sync(entry)
            } else {
                toStop.add(animId)
            }
        }
        for (id in toStop) stopInternal(id)
    }

    /** 更新某次播放的变量。 */
    @JvmStatic
    fun updateVariable(animationId: UUID, name: String, value: String) {
        entries[animationId]?.player?.updateVariable(name, value)
    }

    /** 停止一次或全部播放。 */
    @JvmStatic
    fun stop(animationId: UUID?) {
        if (animationId == null) {
            for (id in entries.keys) stopInternal(id)
        } else {
            stopInternal(animationId)
        }
    }

    private fun sync(entry: Entry) {
        val snap = entry.player.consumeJustLooped()
        for (state in entry.player.currentStates()) {
            val uuid = entry.particleUuids[state.id] ?: continue
            ClientParticleEngine.instance()?.updateParticleDirect(
                uuid, state.pos, state.color, state.scale[0], state.glowing, state.lightLevel, snap
            )
        }
    }

    private fun stopInternal(animationId: UUID) {
        val entry = entries.remove(animationId) ?: return
        ClientParticleEngine.instance()?.destroyParticles(entry.particleUuids.values.toTypedArray())
    }
}
