package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.ParticleAnimation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 客户端动画管理器：本地播放服务端下发的 .pdrawc 动画，每客户端 tick 推进并同步渲染。
 */
object ClientAnimationManager {

    private class Entry(
        val player: ClientAnimationPlayer,
        val particleUuids: MutableMap<String, UUID>,
        val animation: ParticleAnimation,
        // 当前已生成（在场）的状态 id 集合——st 门控的生成/回收以它为基准做差分
        val liveIds: HashSet<String> = HashSet(),
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

    /** 开始本地播放一个动画（解析 .pdrawc 字节并验签，失败则拒绝播放）。 */
    @JvmStatic
    fun play(animationId: UUID, data: ByteArray, origin: Vec3) {
        val animation = try {
            AnimationLoader.parse(data)
        } catch (_: Exception) {
            return
        }
        // 播放前预加载内嵌贴图
        for (texName in animation.textures) {
            val bytes = animation.texData[texName] ?: continue
            try { TextureCache.load(texName, bytes) } catch (_: Exception) {}
        }

        val player = ClientAnimationPlayer(animation, origin)
        val uuids = HashMap<String, UUID>()
        val liveIds = HashSet<String>()
        for (state in player.currentStates()) {
            // st 门控：未出场粒子不生成（隐藏 = 渲染管线中不存在，与 alpha 无关）
            if (!state.visible) continue
            val uuid = UUID.randomUUID()
            uuids[state.id] = uuid
            liveIds.add(state.id)
            ClientParticleEngine.instance()?.spawnParticle(
                uuid, state.pos.x, state.pos.y, state.pos.z,
                state.color.r, state.color.g, state.color.b, state.color.a,
                state.scale[0], -1, null, state.glowing, state.lightLevel, state.uv
            )
        }
        entries[animationId] = Entry(player, uuids, animation, liveIds)
    }

    /** 重载所有正在播放动画的内嵌贴图（/pdraw reload 使用）。 */
    @JvmStatic
    fun reloadTextures() {
        TextureCache.clear()
        for ((_, entry) in entries) {
            for (texName in entry.animation.textures) {
                val bytes = entry.animation.texData[texName] ?: continue
                try { TextureCache.load(texName, bytes) } catch (_: Exception) {}
            }
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
        val engine = ClientParticleEngine.instance() ?: return
        for (state in entry.player.currentStates()) {
            val uuid = entry.particleUuids[state.id] ?: continue
            val live = state.id in entry.liveIds
            when {
                // 出场窗口结束 → 回收（循环回卷后再次满足 st 时重新生成）
                !state.visible && live -> {
                    engine.destroyParticles(arrayOf(uuid))
                    entry.liveIds.remove(state.id)
                }
                // 刚到达 st → 生成
                state.visible && !live -> {
                    engine.spawnParticle(
                        uuid, state.pos.x, state.pos.y, state.pos.z,
                        state.color.r, state.color.g, state.color.b, state.color.a,
                        state.scale[0], -1, null, state.glowing, state.lightLevel, state.uv
                    )
                    entry.liveIds.add(state.id)
                }
                state.visible && live ->
                    ClientParticleEngine.instance()?.updateParticleDirectArray(
                        uuid, state.pos, state.color, state.scale, state.glowing, state.lightLevel, snap
                    )
            }
        }
    }

    private fun stopInternal(animationId: UUID) {
        val entry = entries.remove(animationId) ?: return
        ClientParticleEngine.instance()?.destroyParticles(entry.particleUuids.values.toTypedArray())
    }
}

