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
// 里程口径用锚点的插值位置（partialTick），于是「投射物在渲染帧上走到哪，尾迹就铺到哪」。
internal object ClientEmitterManager {

    /** 单帧最多发射多少颗（掉帧/锚点瞬移时不让一帧炸出几百颗）。 */
    private const val MAX_EMIT_PER_FRAME = 64

    /** 单帧计入的时间上限（毫秒）：切窗口回来/长卡顿不补发一大段。 */
    private const val MAX_FRAME_MS = 250.0

    private class Active(
        val id: UUID,
        val params: EmitterParams,
        val visual: ResolvedVisual,
        var anchor: Anchor,
        var mode: EmitMode,
        var spacing: Double,
        var intervalMs: Int,
    ) {
        var distance: DistanceAdvance? = null
        var time: TimeAdvance? = null

        /** 上一帧的锚点位置（里程口径的段起点）；null = 还没建立基准。 */
        var lastPos: Vec3? = null

        /** 已发射粒子各自的死亡时刻（nanoTime），用于 maxAlive 上限。 */
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
            p,
            ResolvedVisual.of(p.visual, p.scale),
            payload.anchor,
            p.mode,
            p.spacing,
            p.intervalMs,
        )
        emitters[payload.emitterId] = active
    }

    fun update(payload: EmitterUpdatePayload) {
        val active = emitters[payload.emitterId] ?: return
        active.anchor = payload.anchor
        active.mode = payload.mode
        // 口径变了：推进器重建（攒了一半的里程/时间不再沿用，避免换口径时补发一颗）
        if (active.spacing != payload.spacing) {
            active.spacing = payload.spacing
            active.distance = null
        }
        if (active.intervalMs != payload.intervalMs) {
            active.intervalMs = payload.intervalMs
            active.time = null
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

        for (active in emitters.values) emitFrame(active, engine, level, partialTick, deltaMs)
    }

    private fun emitFrame(active: Active, engine: ClientParticleEngine,
                          level: ClientLevel, partialTick: Float, deltaMs: Double) {
        val pos = resolveAnchor(active.anchor, level, partialTick) ?: return
        val prev = active.lastPos
        active.lastPos = pos
        if (prev == null) return // 第一帧只建立基准，不从「上一次位置」补发

        if (active.mode == EmitMode.DISTANCE) {
            val advance = active.distance ?: DistanceAdvance(active.spacing).also { active.distance = it }
            active.scratch.clear()
            advance.advance(prev, pos, active.scratch)
            val count = minOf(active.scratch.size, MAX_EMIT_PER_FRAME)
            for (i in 0 until count) emitOne(active, engine, active.scratch[i])
        } else {
            val advance = active.time ?: TimeAdvance(active.intervalMs.toDouble()).also { active.time = it }
            val count = advance.advance(deltaMs)
            for (i in 0 until count) emitOne(active, engine, pos)
        }
    }

    /**
     * 锚点的**本帧**位置：Fixed 恒定；Entity 按 partialTick 取实体的插值位置（实体不在场返回 null，
     * 这一帧不推进——里程基准保持在原地，实体回来接着铺）；Movable 用「上一 tick 位置 + 速度 × partialTick」。
     */
    private fun resolveAnchor(anchor: Anchor, level: ClientLevel, partialTick: Float): Vec3? = when (anchor) {
        is Anchor.Fixed -> anchor.pos
        is Anchor.Entity -> {
            val entity = level.getEntity(anchor.entityId) ?: return null
            entity.getPosition(partialTick).add(anchor.offset)
        }
        is Anchor.Movable -> anchor.pos.add(anchor.velocity.scale(partialTick.toDouble()))
    }

    private fun emitOne(active: Active, engine: ClientParticleEngine, pos: Vec3) {
        val p = active.params
        val now = System.nanoTime()
        while (active.alive.isNotEmpty() && active.alive.first() <= now) active.alive.removeFirst()
        if (active.alive.size >= p.maxAlive) return

        val id = UUID.randomUUID()
        engine.spawnParticle(
            id, pos.x, pos.y, pos.z,
            p.r, p.g, p.b, p.a, p.scale, p.lifetimeTicks,
            null, p.glowing, p.lightLevel, active.visual, p.lifeCurve,
        )
        active.alive.addLast(now + p.lifetimeTicks * 50_000_000L)
        if (p.velocity.x != 0.0 || p.velocity.y != 0.0 || p.velocity.z != 0.0) {
            engine.setVelocity(id, p.velocity.x, p.velocity.y, p.velocity.z)
        }
    }
}
