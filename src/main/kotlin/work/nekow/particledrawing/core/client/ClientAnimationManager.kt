package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.AnimationProgress
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.PlaybackClock
import work.nekow.particledrawing.animation.timelineLength
import work.nekow.particledrawing.animation.PdrawcReader
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.EffectOptions
import work.nekow.particledrawing.core.network.PlayEffectPayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

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
        // 特效锚点解析器（null = 旧固定 origin 路径）
        val anchor: EffectAnchorResolver? = null,
        // 特效播放时钟（null = 旧 gameTime 时钟路径）
        val clock: PlaybackClock? = null,
        // 生效的 loop（特效可覆盖动画自身 loop）
        val loop: Boolean = true,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()

    // 特效资源缓存：key → .pdrawc 字节（服务端下发后驻留，重复播放不再请求）
    private val effectCache = ConcurrentHashMap<Identifier, ByteArray>()

    // 缓存缺失的待播特效：收到 EffectDataPayload 后再真正开播
    private val pendingEffectPlays = ConcurrentHashMap<UUID, PlayEffectPayload>()

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
    fun play(animationId: UUID, data: ByteArray, origin: Vec3, startGameTick: Long) {
        val animation = try {
            AnimationLoader.parse(data)
        } catch (_: Exception) {
            return
        }
        play(animationId, animation, origin, startGameTick)
    }

    /** 开始本地播放一个代码生成的 [ParticleAnimation]（结构化载荷，不验签）。 */
    @JvmStatic
    fun play(animationId: UUID, animation: ParticleAnimation, origin: Vec3, startGameTick: Long) {
        // 服务端在维度切换/重生/重连后会重发同一播放：先清理旧条目与其粒子，再重建，
        // 避免旧条目残留（旧桥接粒子已随上一世界的 ParticleEngine 销毁，不可复用）。
        if (entries.containsKey(animationId)) stopInternal(animationId)

        // 播放前预加载内嵌贴图
        preloadTextures(animation)

        // 服务端权威进度：以当前维度 gameTime 计算起始帧（重发/迟到加入时直接对齐其他玩家）
        val currentGameTick = Minecraft.getInstance().level?.gameTime ?: startGameTick
        val player = ClientAnimationPlayer(animation, origin, startGameTick, currentGameTick)
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

    // ---- 特效 API（按 key 播放 + 锚点 + 时钟） ----

    /** 收到 PlayEffectPayload：缓存命中直接开播，否则请求服务端下发字节并挂起。 */
    @JvmStatic
    fun playEffect(payload: PlayEffectPayload) {
        val cached = effectCache[payload.key]
        if (cached == null) {
            pendingEffectPlays[payload.playbackId] = payload
            Minecraft.getInstance().connection?.send(
                work.nekow.particledrawing.core.network.EffectRequestPayload(payload.key)
            )
            return
        }
        startEffect(payload.playbackId, cached, payload.key, payload.anchor, payload.options, payload.startGameTick)
    }

    /** 收到服务端下发的特效字节：验签缓存，并开播所有等待该 key 的挂起播放。 */
    @JvmStatic
    fun onEffectData(key: Identifier, data: ByteArray) {
        if (!PdrawcReader.verify(data)) return
        effectCache[key] = data
        val it = pendingEffectPlays.entries.iterator()
        while (it.hasNext()) {
            val (id, payload) = it.next()
            if (payload.key == key) {
                it.remove()
                startEffect(id, data, key, payload.anchor, payload.options, payload.startGameTick)
            }
        }
    }

    /** 纯客户端本地播放入口（ClientEffects 使用）。 */
    @JvmStatic
    fun playLocal(playbackId: UUID, animation: ParticleAnimation, anchor: Anchor, options: EffectOptions) {
        val startGameTick = Minecraft.getInstance().level?.gameTime ?: 0L
        playAnchored(playbackId, animation, anchor, options, startGameTick)
    }

    /** 更新可移动锚点（锚点更新包逐条调用）。 */
    @JvmStatic
    fun updateAnchor(playbackId: UUID, pos: Vec3, velocity: Vec3) {
        entries[playbackId]?.anchor?.updateMovable(pos, velocity)
    }

    /** 应用服务端权威时钟同步（seek/暂停/变速）。 */
    @JvmStatic
    fun applyClockSync(playbackId: UUID, position: Double, playing: Boolean, speed: Double) {
        entries[playbackId]?.clock?.let {
            it.position = position
            it.playing = playing
            it.speed = speed
        }
    }

    @JvmStatic
    fun seekLocal(playbackId: UUID, tick: Double) {
        entries[playbackId]?.clock?.position = tick
    }

    @JvmStatic
    fun pauseLocal(playbackId: UUID) {
        entries[playbackId]?.clock?.playing = false
    }

    @JvmStatic
    fun resumeLocal(playbackId: UUID) {
        entries[playbackId]?.clock?.playing = true
    }

    @JvmStatic
    fun setSpeedLocal(playbackId: UUID, speed: Double) {
        entries[playbackId]?.clock?.speed = speed
    }

    @JvmStatic
    fun isActive(playbackId: UUID): Boolean = entries.containsKey(playbackId)

    private fun startEffect(
        playbackId: UUID,
        data: ByteArray,
        key: Identifier,
        anchor: Anchor,
        options: EffectOptions,
        startGameTick: Long,
    ) {
        val animation = try {
            AnimationLoader.parse(data)
        } catch (_: Exception) {
            return
        }
        playAnchored(playbackId, animation, anchor, options, startGameTick)
    }

    /**
     * 锚定播放：播放器输出本地坐标（origin=ZERO），渲染层每 tick 用锚点解析器映射到世界坐标。
     * 播放器时间轴由 [PlaybackClock] 驱动，支持从指定 tick 开始、暂停、变速与任意 seek。
     */
    private fun playAnchored(
        playbackId: UUID,
        animation: ParticleAnimation,
        anchor: Anchor,
        options: EffectOptions,
        startGameTick: Long,
    ) {
        if (entries.containsKey(playbackId)) stopInternal(playbackId)
        preloadTextures(animation)

        val loop = options.loop() ?: animation.loop
        val maxTick = animation.timelineLength()
        val initialTick = AnimationProgress.tickAt(options.startTick().toLong(), maxTick, loop)
        val currentGameTick = Minecraft.getInstance().level?.gameTime ?: startGameTick
        val player = ClientAnimationPlayer(animation, Vec3.ZERO, startGameTick, currentGameTick, initialTick)
        val resolver = EffectAnchorResolver(anchor, options.scale())
        val clock = PlaybackClock(options.startTick(), playing = true, speed = options.speed())

        val uuids = HashMap<String, UUID>()
        val liveIds = HashSet<String>()
        for (state in player.currentStates()) {
            if (!state.visible) continue
            val uuid = UUID.randomUUID()
            uuids[state.id] = uuid
            liveIds.add(state.id)
            ClientParticleEngine.instance()?.let { spawnState(it, uuid, state, resolver) }
        }
        entries[playbackId] = Entry(player, uuids, animation, Vec3.ZERO, liveIds, resolver, clock, loop)
    }

    /**
     * 客户端世界卸载（切换维度/重生/退出世界）时清理全部本地播放。
     * ClientLevel 重建会换掉原版 ParticleEngine，桥接粒子随之销毁；条目继续存在只会
     * 对着不存在的桥接空转。清理后等待服务端重发 PlayAnimationPayload 重建播放。
     */
    @JvmStatic
    fun onClientLevelUnload() {
        if (entries.isEmpty()) {
            // 即使没有播放条目，也解除可能残留的摄像机预览（姿态属于旧世界）
            CameraController.detach()
            return
        }
        for (id in entries.keys) stopInternal(id)
        CameraController.detach()
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
        // 玩家死亡/重生阶段退出摄像机预览：预览会把相机固定到动画姿态，若远离重生点，
        // 「正在加载地形」会一直等待相机所在区块编译（LevelLoadTracker 以相机位置为准），
        // 直到 30s 超时才放行。死亡时立刻解除，让相机随玩家回到重生点。
        if (CameraController.isActive()) {
            val mc = Minecraft.getInstance()
            val player = mc.player
            if (player == null || player.isDeadOrDying) CameraController.detach()
        }
        // 服务端权威进度时钟：维度 gameTime（与所有客户端、服务器一致）
        val level = Minecraft.getInstance().level ?: return
        val gameTick = level.gameTime
        val toStop = mutableListOf<UUID>()
        for ((animId, entry) in entries) {
            val alive = if (entry.clock != null) {
                entry.clock.advance()
                entry.player.tickExternal(floor(entry.clock.position).toInt())
            } else {
                entry.player.tick(gameTick)
            }
            if (alive) {
                entry.anchor?.resolveEntity(level)
                // 静态动画（粒子状态恒定）跳过每刻的渲染同步；锚定播放即使静态也必须同步（锚点会动）
                if (!entry.player.isStatic() || entry.anchor != null) sync(entry)
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
        // 回卷标记仅用于重置；连续可见粒子不再跳变（snap=false），
        // 让原版渲染在 xo→x 间线性插值，自然穿过 360°≡0° 的闭合帧，循环无缝。
        entry.player.consumeJustLooped()
        val engine = ClientParticleEngine.instance() ?: return
        val currentIds = HashSet<String>()
        for (state in entry.player.currentStates()) {
            currentIds.add(state.id)
            var uuid = entry.particleUuids[state.id]
            val live = state.id in entry.liveIds
            when {
                // 新出现的状态（spawn 运行时动态生成的派生粒子）：首次可见时创建 uuid 并生成桥接粒子
                uuid == null -> {
                    if (state.visible) {
                        uuid = UUID.randomUUID()
                        entry.particleUuids[state.id] = uuid
                        spawnState(engine, uuid, state, entry.anchor)
                        entry.liveIds.add(state.id)
                    }
                }
                // 出场窗口结束 → 回收（循环回卷后再次满足 st 时重新生成）
                !state.visible && live -> {
                    engine.destroyParticles(arrayOf(uuid))
                    entry.liveIds.remove(state.id)
                }
                // 刚到达 st → 生成
                state.visible && !live -> {
                    spawnState(engine, uuid, state, entry.anchor)
                    entry.liveIds.add(state.id)
                }
                state.visible && live -> {
                    val pos = entry.anchor?.apply(state.pos) ?: state.pos
                    ClientParticleEngine.instance()?.updateParticleDirectArray(
                        uuid, pos, state.color, state.scale, state.glowing, state.lightLevel, snap = false
                    )
                }
            }
        }
        // 已不在当前状态中的粒子（被 kill / 运行时重建移除）：销毁桥接粒子并清理索引
        val deadIds = entry.particleUuids.keys.filter { it !in currentIds }
        if (deadIds.isNotEmpty()) {
            engine.destroyParticles(deadIds.map { entry.particleUuids[it]!! }.toTypedArray())
            for (id in deadIds) {
                entry.particleUuids.remove(id)
                entry.liveIds.remove(id)
            }
        }
    }

    private fun stopInternal(animationId: UUID) {
        val entry = entries.remove(animationId) ?: return
        if (CameraController.activeAnimationId() == animationId) CameraController.detach()
        ClientParticleEngine.instance()?.destroyParticles(entry.particleUuids.values.toTypedArray())
    }

    private fun spawnState(
        engine: ClientParticleEngine,
        uuid: UUID,
        state: ClientAnimationPlayer.ParticleState,
        resolver: EffectAnchorResolver? = null,
    ) {
        val pos = resolver?.apply(state.pos) ?: state.pos
        engine.spawnParticle(
            uuid, pos.x, pos.y, pos.z,
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

