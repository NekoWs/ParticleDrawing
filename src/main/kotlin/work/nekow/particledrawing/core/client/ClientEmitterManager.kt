package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.EmitMode
import work.nekow.particledrawing.core.network.EmitterParams
import work.nekow.particledrawing.core.network.EmitterSpawnPayload
import work.nekow.particledrawing.core.network.EmitterUpdatePayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// 客户端发射器运行时：服务端只声明一次「沿哪个锚点、按什么口径、发什么样的粒子」，
// 之后由这里按**渲染帧**推进里程/时间并就地生成粒子（零逐帧带宽、出现时刻不被 tick 量化）。
// 锚点用相邻两个服务器样本插值（见 MovableAnchorSamples），于是发射前沿与 track 头部同相位。
internal object ClientEmitterManager {

    /** 单帧最多发射多少颗（掉帧/锚点瞬移时不让一帧炸出几百颗）。 */
    private const val MAX_EMIT_PER_FRAME = 64

    /** 单帧计入的时间上限（毫秒）：切窗口回来/长卡顿不补发一大段。 */
    private const val MAX_FRAME_MS = 250.0

    private class Active(
        val id: UUID,
        val seed: Long,
        var params: EmitterParams,
        var visual: ResolvedVisual,
        var anchor: Anchor,
    ) {
        var distance: DistanceAdvance? = null
        var time: TimeAdvance? = null

        /** 移动锚点的相邻样本（Fixed/Entity 锚点不用）。 */
        val samples = MovableAnchorSamples()

        /** 上一帧的锚点位置（里程口径的段起点）；null = 还没建立基准。 */
        var lastPos: Vec3? = null

        /** 上一帧的运动方向（时间口径的抖动平面用它）。 */
        var lastDir: Vec3 = Vec3(0.0, 0.0, 1.0)

        /** 已发射的颗数：逐颗抖动的确定性哈希靠它。 */
        var emissionIndex: Long = 0L

        /** 已发射粒子各自的到期引擎 tick，用于 maxAlive 上限（与寿命同一时钟：暂停时不流逝）。 */
        val alive = ArrayDeque<Long>()

        /** 一帧内要发射的位置（复用列表，避免每帧分配）。 */
        val scratch = ArrayList<Vec3>(8)
    }

    private val emitters = ConcurrentHashMap<UUID, Active>()
    private var lastFrameNanos = 0L

    fun spawn(payload: EmitterSpawnPayload) {
        val p = payload.params
        val active = Active(
            payload.emitterId,
            EmitterSampling.seedOf(payload.emitterId),
            p,
            ResolvedVisual.of(p.visual, p.scale),
            payload.anchor,
        )
        (payload.anchor as? Anchor.Movable)?.let {
            active.samples.onSample(it.pos, it.velocity, System.nanoTime())
        }
        emitters[payload.emitterId] = active
    }

    /** 运行期变更：只应用载荷里带了的段（锚点 / 口径 / 整份静态参数）。 */
    fun update(payload: EmitterUpdatePayload) {
        val active = emitters[payload.emitterId] ?: return
        val now = System.nanoTime()

        payload.anchor?.let { anchor ->
            active.anchor = anchor
            if (anchor is Anchor.Movable) {
                active.samples.onSample(anchor.pos, anchor.velocity, now)
            }
        }

        payload.cadence?.let { c ->
            val old = active.params
            if (old.mode != c.mode || old.spacing != c.spacing || old.intervalMs != c.intervalMs) {
                active.params = old.copy(mode = c.mode, spacing = c.spacing, intervalMs = c.intervalMs)
                // 口径变了：推进器重建（攒了一半的里程/时间不再沿用，避免换口径时补发一颗）
                active.distance = null
                active.time = null
            }
        }

        payload.params?.let { p ->
            val cadenceChanged = p.mode != active.params.mode || p.spacing != active.params.spacing
            active.params = p
            active.visual = ResolvedVisual.of(p.visual, p.scale)
            if (cadenceChanged) active.distance = null
        }
    }

    fun stop(emitterId: UUID) {
        emitters.remove(emitterId)
    }

    /** 维度卸载 / 断线：全部清掉（已生成的粒子由粒子引擎自己走完寿命）。 */
    fun clearAll() {
        emitters.clear()
        lastFrameNanos = 0L
    }

    /** 当前活跃发射器数量（调试用）。 */
    fun activeCount(): Int = emitters.size

    /**
     * 每渲染帧推进（由 `ParticleRenderHandler.onRenderFrame` 调用）。
     *
     * 暂停时不推进：单人 Esc 暂停时世界不走，尾迹也不该继续长。
     */
    fun frameTick(partialTick: Float) {
        if (emitters.isEmpty()) {
            lastFrameNanos = 0L
            return
        }
        val mc = Minecraft.getInstance()
        if (mc.isPaused) return
        val level = mc.level ?: return
        val engine = ClientParticleEngine.instance() ?: return

        val now = System.nanoTime()
        val deltaMs = if (lastFrameNanos == 0L) {
            0.0
        } else {
            ((now - lastFrameNanos) / 1_000_000.0).coerceIn(0.0, MAX_FRAME_MS)
        }
        lastFrameNanos = now

        for (active in emitters.values) emitFrame(active, engine, level, partialTick, deltaMs, now)
    }

    private fun emitFrame(active: Active, engine: ClientParticleEngine, level: ClientLevel,
                          partialTick: Float, deltaMs: Double, now: Long) {
        val pos = resolveAnchor(active, level, partialTick, now) ?: return
        val prev = active.lastPos
        active.lastPos = pos
        if (prev == null) return // 第一帧只建立基准，不从「上一次位置」补发

        val dir = frameDirection(active, pos, prev)
        if (active.params.mode == EmitMode.DISTANCE) {
            val advance = active.distance
                ?: DistanceAdvance(active.params.spacing).also { active.distance = it }
            active.scratch.clear()
            advance.advance(prev, pos, active.scratch)
            val count = minOf(active.scratch.size, MAX_EMIT_PER_FRAME)
            for (i in 0 until count) emitOne(active, engine, active.scratch[i], dir)
        } else {
            val advance = active.time
                ?: TimeAdvance(active.params.intervalMs.toDouble()).also { active.time = it }
            val count = advance.advance(deltaMs)
            for (i in 0 until count) emitOne(active, engine, pos, dir)
        }
    }

    /** 本帧的运动方向：优先用锚点这一帧的位移，退化时沿用上一帧（原地发射也要有稳定的抖动平面）。 */
    private fun frameDirection(active: Active, pos: Vec3, prev: Vec3): Vec3 {
        val delta = pos.subtract(prev)
        val dir = if (delta.lengthSqr() > 1e-10) delta.normalize() else active.lastDir
        active.lastDir = dir
        return dir
    }

    /**
     * 锚点的**本帧**位置：
     * - Fixed 恒定；
     * - Entity 取实体的插值位置（实体不在场返回 null，这一帧不推进）；
     * - Movable 用相邻两个服务器样本插值（瞬移/断流的语义见 [MovableAnchorSamples]）。
     */
    private fun resolveAnchor(active: Active, level: ClientLevel, partialTick: Float, now: Long): Vec3? =
        when (val anchor = active.anchor) {
            is Anchor.Fixed -> anchor.pos
            is Anchor.Entity -> {
                val entity = level.getEntity(anchor.entityId) ?: return null
                entity.getPosition(partialTick).add(anchor.offset)
            }
            is Anchor.Movable -> active.samples.resolve(partialTick, now)
        }

    private fun emitOne(active: Active, engine: ClientParticleEngine, pos: Vec3, dir: Vec3) {
        val p = active.params
        val nowTick = ClientParticleEngine.tickSequence()
        while (active.alive.isNotEmpty() && active.alive.first() <= nowTick) active.alive.removeFirst()
        if (active.alive.size >= p.maxAlive) return

        val index = active.emissionIndex++
        val offset = EmitterSampling.offset(active.seed, index, dir, p.jitter, p.offsetAlong)
        val spawnPos = if (offset.lengthSqr() == 0.0) pos else pos.add(offset)

        val id = UUID.randomUUID()
        engine.spawnParticle(
            id, spawnPos.x, spawnPos.y, spawnPos.z,
            p.r, p.g, p.b, p.a, p.scale, p.lifetimeTicks,
            null, p.glowing, p.lightLevel, active.visual, p.lifeCurve,
        )
        active.alive.addLast(nowTick + p.lifetimeTicks)
        if (p.velocity.x != 0.0 || p.velocity.y != 0.0 || p.velocity.z != 0.0) {
            engine.setVelocity(id, p.velocity.x, p.velocity.y, p.velocity.z)
        }
    }
}
