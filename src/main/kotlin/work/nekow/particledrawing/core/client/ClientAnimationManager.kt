package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.ClientAnimationPlayer
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

    /** 开始本地播放一个动画。 */
    @JvmStatic
    fun play(animationId: UUID, json: String, origin: Vec3) {
        val animation = try {
            AnimationLoader.parse(json)
        } catch (_: Exception) {
            return
        }
        val player = ClientAnimationPlayer(animation, origin)
        val uuids = HashMap<String, UUID>()
        for ((id, style, pos, color, scale, glowing, lightLevel) in player.currentStates()) {
            val uuid = UUID.randomUUID()
            uuids[id] = uuid
            ClientParticleEngine.instance()?.spawnParticle(
                uuid, style, pos.x, pos.y, pos.z,
                color.r, color.g, color.b, color.a,
                scale, -1, null, glowing, lightLevel
            )
        }
        entries[animationId] = Entry(player, uuids)
    }

    /** 每客户端 tick 推进所有动画并同步渲染。 */
    @JvmStatic
    fun tick() {
        val toStop = mutableListOf<UUID>()
        for ((animId, entry) in entries) {
            if (entry.player.tick()) {
                sync(entry)
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
                uuid,
                state.pos.x, state.pos.y, state.pos.z,
                state.color.r, state.color.g, state.color.b, state.color.a,
                state.scale,
                state.glowing, state.lightLevel,
                snap
            )
        }
    }

    private fun stopInternal(animationId: UUID) {
        val entry = entries.remove(animationId) ?: return
        ClientParticleEngine.instance()?.destroyParticles(entry.particleUuids.values.toTypedArray())
    }
}
