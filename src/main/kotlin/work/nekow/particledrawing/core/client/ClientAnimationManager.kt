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
        // 播放原点（世界坐标）：摄像机预览姿态需加此偏移才与粒子同处一个世界
        val origin: Vec3,
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

    /** 一次摄像机匹配结果（/pdraw camera 使用）。 */
    data class CameraTarget(
        val animationId: UUID,
        val cameraId: String,
        val cameraName: String,
        val pose: ClientAnimationPlayer.CameraPose,
        val origin: Vec3,
    )

    /**
     * 在所有播放中的动画里按 id 或 name 匹配一个摄像机，并返回其在当前 tick 的姿态与播放原点。
     * 无匹配或姿态求值失败时返回 null。
     */
    @JvmStatic
    fun findCamera(camIdOrName: String): CameraTarget? {
        for ((animId, e) in entries) {
            val cam = e.animation.cameras.firstOrNull { it.id == camIdOrName || it.name == camIdOrName } ?: continue
            val pose = e.player.cameraPoseAt(cam.id, e.player.currentTickValue.toDouble()) ?: continue
            return CameraTarget(animId, cam.id, cam.name, pose, e.origin)
        }
        return null
    }

    /** 列出所有播放中动画的摄像机 id 与 name（供 /pdraw camera 参数补全）。 */
    @JvmStatic
    fun listCameras(): List<String> {
        val out = LinkedHashSet<String>()
        for ((_, e) in entries) {
            for (cam in e.animation.cameras) {
                out.add(cam.id)
                out.add(cam.name)
            }
        }
        return out.toList()
    }

    /** 开始本地播放一个动画（解析 .pdrawc 字节并验签，失败则拒绝播放）。 */
    @JvmStatic
    fun play(animationId: UUID, data: ByteArray, origin: Vec3) {
        val animation = try {
            AnimationLoader.parse(data)
        } catch (_: Exception) {
            return
        }
        // 播放前预加载内嵌贴图
        preloadTextures(animation)

        val player = ClientAnimationPlayer(animation, origin)
        val uuids = HashMap<String, UUID>()
        val liveIds = HashSet<String>()
        for (state in player.currentStates()) {
            // st 门控：未出场粒子不生成（隐藏 = 渲染管线中不存在，与 alpha 无关）
            if (!state.visible) continue
            val uuid = UUID.randomUUID()
            uuids[state.id] = uuid
            liveIds.add(state.id)
            ClientParticleEngine.instance()?.let { spawnState(it, uuid, state) }
        }
        entries[animationId] = Entry(player, uuids, animation, origin, liveIds)
    }

    /** 重载所有正在播放动画的内嵌贴图（/pdraw reload 使用）。 */
    @JvmStatic
    fun reloadTextures() {
        TextureCache.clear()
        for ((_, entry) in entries) {
            preloadTextures(entry.animation)
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
        updateCameraPreview()
    }

    /** 刷新「切换到摄像机」预览姿态；绑定的播放已不存在时自动退出预览。 */
    private fun updateCameraPreview() {
        val animId = CameraController.activeAnimationId() ?: run { CameraController.updatePose(null); return }
        val entry = entries[animId] ?: run { CameraController.detach(); return }
        val camId = CameraController.activeCameraId() ?: return
        val pose = entry.player.cameraPoseAt(camId, entry.player.currentTickValue.toDouble())
        CameraController.updatePose(pose)
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
                    spawnState(engine, uuid, state)
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
        if (CameraController.activeAnimationId() == animationId) CameraController.detach()
        ClientParticleEngine.instance()?.destroyParticles(entry.particleUuids.values.toTypedArray())
    }

    private fun spawnState(engine: ClientParticleEngine, uuid: UUID, state: ClientAnimationPlayer.ParticleState) {
        engine.spawnParticle(
            uuid, state.pos.x, state.pos.y, state.pos.z,
            state.color.r, state.color.g, state.color.b, state.color.a,
            state.scale[0], -1, null, state.glowing, state.lightLevel, state.uv
        )
    }

    private fun preloadTextures(animation: ParticleAnimation) {
        for (texName in animation.textures) {
            val bytes = animation.texData[texName] ?: continue
            try { TextureCache.load(texName, bytes) } catch (_: Exception) {}
        }
    }
}

