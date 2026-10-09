package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.util.AttachMath
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 客户端粒子引擎，管理渲染粒子的生命周期、桥接与每帧同步。
 */
@Suppress("unused")
class ClientParticleEngine {

    private val particles: MutableMap<UUID, RenderParticle> = ConcurrentHashMap()
    private val bridges: MutableMap<UUID, BridgeParticle> = ConcurrentHashMap()
    private val groups: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap()

    // 动画本地播放直接同步的粒子：跳过 frameUpdate 的缓动轮转
    private val takeover = ParticleTakeover()

    // 发光粒子索引：增量维护，避免 getGlowingParticles 每帧遍历全部粒子
    private val glowingIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // 带速度的运动粒子索引：每 client tick 确定性积分一次，不参与缓动轮转
    private val motionIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // track 的逐 tick 插值缓冲（网络线程写、客户端主线程每 tick 消费一条）
    private val trackBuffers: MutableMap<UUID, TrackBuffer> = ConcurrentHashMap()

    // 带寿命曲线的粒子：每渲染帧刷新颜色/缩放（淡出/收缩逐帧才连续）。
    // 位置不在这里动：每帧改写 xo/x 会把插值端点折叠成同值对，破坏 partialTick 扫掠。
    private val curveIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // 实体锚点：客户端每 tick 本地解析（服务端只在挂载时下发一次）
    private val attachments: MutableMap<UUID, Attachment> = ConcurrentHashMap()

    /** 一条实体锚点记录：uuid 是主身份，网络 id 只是解析兜底。 */
    private class Attachment(val entityId: Int, val uuid: UUID?, val offset: Vec3, val local: Boolean)

    private var syncCursor = 0
    private var cachedIds: Array<UUID> = emptyArray()
    private var cachedSize = -1

    /**
     * 生成一个新粒子并注册到原版粒子系统。
     * @param scaleArray 非均匀缩放 [sx, sy, sz]；给了就用它，否则用标量 [scale]
     * @param lifeCurve 逐粒子寿命曲线（寿命内的颜色/尺寸乘数）；null = 恒定外观
     * @param prev 上一 tick 的位置：给了就让首帧从 [prev] 插值到当前位置；null = 直接在当前位置出生
     */
    fun spawnParticle(id: UUID, x: Double, y: Double, z: Double,
                      r: Float, g: Float, b: Float, a: Float, scale: Float,
                      lifetimeTicks: Int, groupId: UUID?, glowing: Boolean, lightLevel: Int,
                      uv: UvData? = null, billboard: Boolean = true,
                      spin: DoubleArray = ZERO_SPIN, spinLocal: Boolean = true,
                      additive: Boolean = false, scaleArray: FloatArray? = null,
                      lifeCurve: ParticleLifeCurve? = null, prev: Vec3? = null) {
        if (particles.size >= ParticleDrawingConfig.CLIENT.maxRenderParticles.get()) return

        // 同一个 id 重新 spawn（服务端重发、迟到同步）：先把旧的整条状态摘掉
        if (particles.containsKey(id)) {
            removeOne(id)
            for (memberSet in groups.values) memberSet.remove(id)
        }

        val rp = RenderParticle(id, Vec3(x, y, z),
            Color.of(r, g, b, a), scale, glowing, lightLevel, lifetimeTicks, uv, lifeCurve, prev)
        if (scaleArray != null) rp.setScaleArrayDirect(scaleArray)
        particles[id] = rp
        if (glowing && lightLevel > 0) glowingIds.add(id)
        if (lifeCurve != null && !lifeCurve.isEmpty()) curveIds.add(id)

        val pe: ParticleEngine = Minecraft.getInstance().particleEngine
        val level = Minecraft.getInstance().level
        if (level != null) {
            // 有 prev 就先把桥接粒子放在 prev，再按非跳变写当前位置，
            // 于是 xo/x 是一对「上一 tick → 本 tick」的端点，原版渲染按 partialTick 扫掠
            val start = prev ?: Vec3(x, y, z)
            val bp = BridgeParticle(id, level, start.x, start.y, start.z,
                Color.of(r, g, b, a), scale, glowing, uv, additive)
            if (scaleArray != null) bp.syncScaleArray(scaleArray)
            bp.syncOrientation(billboard, spin, spinLocal)
            if (prev != null) bp.syncPosition(x, y, z, snap = false)
            pe.add(bp)
            bridges[id] = bp
        }

        if (groupId != null) {
            groups.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(id)
        }
    }

    /** [spawnParticle] 的外观规格版本：网络 spawn 包与客户端本地发射器都走这里。 */
    internal fun spawnParticle(id: UUID, x: Double, y: Double, z: Double,
                      r: Float, g: Float, b: Float, a: Float, scale: Float,
                      lifetimeTicks: Int, groupId: UUID?, glowing: Boolean, lightLevel: Int,
                      visual: ResolvedVisual, lifeCurve: ParticleLifeCurve? = null, prev: Vec3? = null) {
        spawnParticle(
            id, x, y, z, r, g, b, a, scale, lifetimeTicks, groupId, glowing, lightLevel,
            visual.uv, visual.billboard, visual.spin, visual.spinLocal, visual.additive,
            visual.scaleArray, lifeCurve, prev,
        )
    }

    /**
     * 更新现有粒子的属性并设置缓动过渡目标；只处理 hasPos / hasColor / hasScale 标记的分量。
     *
     * @param durationTicks 过渡时长（tick）；0 = 立即落地
     */
    fun updateParticle(id: UUID, x: Double, y: Double, z: Double,
                       r: Float, g: Float, b: Float, a: Float, scale: Float,
                       hasPos: Boolean, hasColor: Boolean, hasScale: Boolean,
                       durationTicks: Int, easing: EasingType) {
        val rp = particles[id] ?: return
        takeover.clear(id)
        if (hasPos) {
            motionIds.remove(id) // 位置更新会把速度清零
            trackBuffers.remove(id) // 缓动接管位置，丢开 track 缓冲
        }

        if (hasPos) {
            if (durationTicks == 0) {
                rp.snapPosition(x, y, z)
            } else {
                rp.setPositionTarget(x, y, z, easing, durationTicks * 50L)
            }
        }

        if (hasColor || hasScale) {
            val color = Color.of(
                if (hasColor) r else rp.r(), if (hasColor) g else rp.g(),
                if (hasColor) b else rp.b(), if (hasColor) a else rp.a())
            val scl = if (hasScale) scale else rp.scale()
            rp.setTargetColorScale(color, scl, easing, durationTicks * 50L)
            if (durationTicks <= 0) {
                // 零时长目标立即落地，不等分批轮转，否则紧随其后的缓动包会把起点读成旧值；
                // 两端一起写：即时改色/改尺寸是跳变，不插值成一段渐变
                rp.finishColorScale()
                bridges[id]?.let {
                    it.syncColor(rp.r(), rp.g(), rp.b(), rp.a(), snap = true)
                    it.syncScale(rp.scale(), snap = true)
                }
            }
        }
    }

    /**
     * 直接同步粒子的完整可视化状态（绕过缓动状态机与分批轮转），供客户端本地动画播放使用。
     * 位置以 snap=false 写入，保留 xo 与 x 的差值，原版渲染按 partialTick 在两者间插值。
     *
     * @param lightLevel 发光粒子向外发出的光照等级 (0-15)
     */
    fun updateParticleDirect(id: UUID, x: Double, y: Double, z: Double,
                             r: Float, g: Float, b: Float, a: Float, scale: Float,
                             glowing: Boolean, lightLevel: Int,
                              snap: Boolean = false) {
        updateParticleDirect(id, Vec3(x, y, z), Color.of(r, g, b, a), scale, glowing, lightLevel, snap)
    }

    /** 直接同步粒子状态（接收 Vec3/Color 引用，避免逐 tick 重复分配对象）。 */
    fun updateParticleDirect(id: UUID, pos: Vec3, color: Color, scale: Float,
                             glowing: Boolean, lightLevel: Int, snap: Boolean = false) {
        applyDirect(id, pos, color, glowing, lightLevel, snap, scale, null)
    }

    /**
     * 直接同步粒子状态（非均匀缩放三分量版本）。
     * scaleArray [sx, sy, sz] 中 sx → quad 宽度，sy → quad 高度，sz 存储但不参与 billboard。
     */
    fun updateParticleDirectArray(id: UUID, pos: Vec3, color: Color, scaleArray: FloatArray,
                                  glowing: Boolean, lightLevel: Int, snap: Boolean = false,
                                  billboard: Boolean = true, spin: DoubleArray = ZERO_SPIN,
                                  spinLocal: Boolean = true) {
        applyDirect(id, pos, color, glowing, lightLevel, snap, 0f, scaleArray, billboard, spin, spinLocal)
    }

    private fun applyDirect(id: UUID, pos: Vec3, color: Color,
                            glowing: Boolean, lightLevel: Int, snap: Boolean,
                            scale: Float, scaleArray: FloatArray?,
                            billboard: Boolean = true, spin: DoubleArray = ZERO_SPIN,
                            spinLocal: Boolean = true) {
        val rp = particles[id] ?: return
        takeover.markAppearance(id)
        trackBuffers.remove(id) // 动画直写接管位置，丢开 track 缓冲
        attachments.remove(id)
        val wasGlowing = rp.glowing() && rp.lightLevel() > 0
        rp.setPositionDirect(pos)
        rp.setColorDirect(color)
        if (scaleArray != null) rp.setScaleArrayDirect(scaleArray) else rp.setScaleDirect(scale)
        rp.setGlowing(glowing)
        rp.setLightLevel(lightLevel)
        val nowGlowing = glowing && lightLevel > 0
        if (wasGlowing != nowGlowing) {
            if (nowGlowing) glowingIds.add(id) else glowingIds.remove(id)
        }
        bridges[id]?.let {
            it.syncPosition(pos.x, pos.y, pos.z, snap)
            it.syncColor(color.r, color.g, color.b, color.a)
            if (scaleArray != null) it.syncScaleArray(scaleArray) else it.syncScale(scale)
            it.setGlowing(glowing)
            it.syncOrientation(billboard, spin, spinLocal)
        }
    }

    /**
     * 设置粒子的加速度（服务端权威力）：客户端按同一规则逐 tick 积分（速度 += 加速度，位置 += 速度）。
     *
     * @param ticks >0 = 施加这么多 tick；<0 = 无限；0 = 清除
     */
    fun setAcceleration(id: UUID, ax: Double, ay: Double, az: Double, ticks: Int) {
        takeover.clear(id)
        trackBuffers.remove(id)
        attachments.remove(id)
        val rp = particles[id] ?: return
        rp.setAcceleration(Vec3(ax, ay, az), ticks)
        if (ticks != 0) {
            motionIds.add(id)
        } else if (rp.velocity().lengthSqr() == 0.0) {
            motionIds.remove(id)
        }
    }

    /**
     * 把粒子挂到实体锚点上：位置由客户端每 tick 本地解析（实体位置 + 偏移，[local] 时偏移随实体朝向）。
     * 锚点接管位置，track 缓冲与速度积分一并让位；实体不在场时保持上次位置。
     *
     * @param entityUuid 非 null 时优先按它解析
     */
    fun attachParticle(id: UUID, entityId: Int, entityUuid: UUID?, ox: Double, oy: Double, oz: Double, local: Boolean) {
        takeover.markPosition(id)
        motionIds.remove(id)
        trackBuffers.remove(id)
        attachments[id] = Attachment(entityId, entityUuid, Vec3(ox, oy, oz), local)
    }

    /** 每 tick 解析全部实体锚点，把结果写进渲染粒子与桥接粒子（原版按 partialTick 插值）。 */
    private fun resolveAttachments() {
        if (attachments.isEmpty()) return
        val level = Minecraft.getInstance().level ?: return
        for ((id, att) in attachments) {
            val entity = att.uuid?.let { ClientAnimationProgramManager.findEntity(it) }
                ?: level.getEntity(att.entityId)
                ?: continue // 实体不在场：保持上次位置
            val pos = AttachMath.resolve(entity.position(), entity.yRot, entity.xRot, att.offset, att.local)
            particles[id]?.setPositionDirect(pos)
            bridges[id]?.syncPosition(pos.x, pos.y, pos.z, snap = false)
        }
    }

    /**
     * 设置粒子的速度向量（blocks/tick）：速度驱动接管位置。
     */
    fun setVelocity(id: UUID, vx: Double, vy: Double, vz: Double) {
        takeover.clear(id)
        trackBuffers.remove(id) // 速度驱动接管位置，丢开 track 缓冲
        attachments.remove(id)  // 速度驱动位置，留着锚点会覆盖它
        particles[id]?.setVelocity(Vec3(vx, vy, vz))
        if (vx != 0.0 || vy != 0.0 || vz != 0.0) motionIds.add(id) else motionIds.remove(id)
    }

    /**
     * 直设粒子位置（无缓动）：目标进入该粒子的插值缓冲，之后每个客户端 tick 消费一条。
     * 某 tick 没收到新位置时端点原地保持，见 [TrackBuffer]。
     */
    fun trackParticle(id: UUID, x: Double, y: Double, z: Double) {
        trackBuffers.computeIfAbsent(id) { TrackBuffer() }.offer(Vec3(x, y, z))
    }

    /**
     * 设置粒子的旋转目标（绕轴心做圆弧运动）。
     */
    fun rotateParticle(id: UUID, px: Double, py: Double, pz: Double,
                       ox: Double, oy: Double, oz: Double,
                       rx: Double, ry: Double, rz: Double,
                       durationTicks: Int, easing: EasingType) {
        takeover.clear(id)
        motionIds.remove(id) // 旋转指令会把速度清零
        trackBuffers.remove(id)
        particles[id]?.setRotation(
            Vec3(px, py, pz), Vec3(ox, oy, oz),
            doubleArrayOf(rx, ry, rz), easing, durationTicks * 50L
        )
    }

    /**
     * 设置粒子的平移目标（绕轴心叠加世界空间增量）。
     */
    fun translateParticle(id: UUID, px: Double, py: Double, pz: Double,
                          ox: Double, oy: Double, oz: Double,
                          tx: Double, ty: Double, tz: Double,
                          durationTicks: Int, easing: EasingType) {
        takeover.clear(id)
        motionIds.remove(id) // 平移指令会把速度清零
        trackBuffers.remove(id)
        particles[id]?.setTranslation(
            Vec3(px, py, pz), Vec3(ox, oy, oz),
            Vec3(tx, ty, tz), easing, durationTicks * 50L
        )
    }

    /**
     * 设置粒子的位置（组 set 位置轨道）：缓动未旋转偏移，保留旋转。
     */
    fun setPosition(id: UUID, px: Double, py: Double, pz: Double,
                    ox: Double, oy: Double, oz: Double,
                    durationTicks: Int, easing: EasingType) {
        takeover.clear(id)
        motionIds.remove(id) // 组 set 位置轨道会把速度清零
        trackBuffers.remove(id)
        particles[id]?.setPositionSet(
            Vec3(px, py, pz), Vec3(ox, oy, oz), easing, durationTicks * 50L
        )
    }

    /**
     * 动态修改粒子的发光光照等级。
     * @param level 目标等级，自动钳制到 [0, 15]
     */
    fun setLightLevel(id: UUID, level: Int) {
        particles[id]?.setLightLevel(level)
    }

    /**
     * 销毁指定粒子并从所有分组中移除。
     */
    fun destroyParticles(ids: Array<UUID>) {
        for (id in ids) removeOne(id)
        for (gms in groups.values) {
            for (id in ids) gms.remove(id)
        }
    }

    /**
     * 摘掉一个粒子：桥接粒子、各索引与 track 缓冲一并清掉。
     */
    private fun removeOne(id: UUID) {
        particles.remove(id)
        bridges.remove(id)?.remove()
        takeover.clear(id)
        glowingIds.remove(id)
        motionIds.remove(id)
        trackBuffers.remove(id)
        attachments.remove(id)
        curveIds.remove(id)
    }

    /** 引擎 tick 序号，每次 [frameUpdate] 自增；桥接粒子据此记录外观端点。 */
    @Volatile
    private var tickSequenceCounter: Long = 0L

    /**
     * 每帧更新：驱动粒子缓动并同步到桥接粒子。
     */
    fun frameUpdate() {
        // 抬 tick 序号：桥接粒子据此把「上一 tick 的外观端点」记下来，供渲染帧插值
        tickSequenceCounter++
        tickSequence = tickSequenceCounter
        // 先推进 track 粒子的插值端点（每 tick 一段，没收到新位置就原地保持）
        advanceTrackedParticles()
        // 实体锚点：本地解析位置（无逐 tick 带宽）
        resolveAttachments()
        // 再确定性推进带速度/力的运动粒子（每 tick 一次，不参与缓动轮转）
        syncMotionParticles()
        // 其余粒子走公平轮转的缓动同步
        syncParticlesInBatches(motionIds)

        val deadIds = ArrayList<UUID>()
        for ((id, rp) in particles) {
            // 寿命与缓动都按引擎时钟（这里每 tick 推一次）：关卡暂停时不流逝
            rp.advanceEngine()
            if (rp.isDead()) deadIds.add(id)
        }
        for (id in deadIds) removeOne(id)
        if (deadIds.isNotEmpty()) {
            for (memberSet in groups.values) {
                for (id in deadIds) memberSet.remove(id)
            }
        }

        groups.values.removeIf { it.isEmpty() }
    }

    /**
     * 每客户端 tick 推进 track 粒子的插值端点：缓冲里有新位置就前进一段
     * （xo = 上一条权威位置、x = 新位置），没有就把端点原地保持（xo = x = 上次位置）。
     */
    private fun advanceTrackedParticles() {
        if (trackBuffers.isEmpty()) return
        val it = trackBuffers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val buffer = entry.value
            if (buffer.advance()) {
                val pos = buffer.current ?: continue
                applyTrackPosition(entry.key, pos, snap = false)
            } else {
                val pos = buffer.current ?: continue
                applyTrackPosition(entry.key, pos, snap = true)
            }
        }
    }

    /** 把 track 位置写进渲染粒子与桥接粒子：直接定位，跳过缓动轮转与运动积分。 */
    private fun applyTrackPosition(id: UUID, pos: Vec3, snap: Boolean) {
        takeover.markPosition(id)
        motionIds.remove(id)
        attachments.remove(id)
        val rp = particles[id]
        if (rp != null) {
            // 位置接管：丢掉速度与力，否则下一 tick 又会自己走
            rp.setVelocity(Vec3.ZERO)
            rp.setAcceleration(Vec3.ZERO, 0)
            rp.setPositionDirect(pos)
        }
        bridges[id]?.syncPosition(pos.x, pos.y, pos.z, snap)
    }

    /**
     * 每 tick 确定性推进所有带速度的粒子，不参与缓动轮转。
     */
    private fun syncMotionParticles() {
        if (motionIds.isEmpty()) return
        val it = motionIds.iterator()
        while (it.hasNext()) {
            val id = it.next()
            val rp = particles[id]
            if (rp == null) {
                it.remove()
                continue
            }
            if (takeover.hasPosition(id)) continue // 已被动画直接接管
            if (rp.velocity().lengthSqr() == 0.0 && !rp.hasActiveForce()) {
                it.remove()
                continue
            }
            val wasSnap = rp.consumeSnap()
            rp.tick()
            syncStateToBridge(rp, wasSnap)
        }
    }

    /**
     * 按轮转顺序分批推进非运动粒子的缓动同步。
     */
    private fun syncParticlesInBatches(motionParticles: Set<UUID>) {
        if (particles.size != cachedSize) {
            cachedIds = particles.keys.toTypedArray()
            cachedSize = particles.size
            if (syncCursor >= cachedIds.size) syncCursor = 0
        }

        val n = cachedIds.size
        if (n == 0) return

        val batch = ParticleDrawingConfig.CLIENT.particleBatchSize.get().coerceAtLeast(1)
        val limit = minOf(batch, n)
        var processed = 0
        while (processed < limit) {
            val id = cachedIds[syncCursor % n]
            syncCursor = (syncCursor + 1) % n
            processed++

            val rp = particles[id] ?: continue
            if (rp.id() in motionParticles) continue
            if (takeover.hasPosition(rp.id())) continue

            val wasSnap = rp.consumeSnap()
            rp.tick()
            syncStateToBridge(rp, wasSnap)
        }
    }

    /** 曲线乘数复用缓冲（客户端渲染单线程，无需每个粒子分配数组）。 */
    private val curveMulBuf = FloatArray(5)
    private val curveScaleBuf = FloatArray(3)

    /**
     * 把渲染粒子的当前位置/颜色/缩放写进桥接粒子；带寿命曲线的粒子在这里乘上曲线乘数。
     */
    private fun syncStateToBridge(rp: RenderParticle, snap: Boolean) {
        val bp = bridges[rp.id()] ?: return
        bp.syncPosition(rp.x(), rp.y(), rp.z(), snap)
        syncAppearanceToBridge(rp, bp)
    }

    /** 颜色/缩放同步（含寿命曲线乘数）；位置不动，所以渲染帧也能安全调用。 */
    private fun syncAppearanceToBridge(rp: RenderParticle, bp: BridgeParticle) {
        if (rp.curveMultipliers(curveMulBuf)) {
            bp.syncColor(
                rp.r() * curveMulBuf[0], rp.g() * curveMulBuf[1],
                rp.b() * curveMulBuf[2], rp.a() * curveMulBuf[3],
            )
            val sa = rp.scaleArray()
            val m = curveMulBuf[4]
            curveScaleBuf[0] = sa[0] * m
            curveScaleBuf[1] = sa[1] * m
            curveScaleBuf[2] = sa[2]
            bp.syncScaleArray(curveScaleBuf)
            return
        }
        bp.syncColor(rp.r(), rp.g(), rp.b(), rp.a())
        val sa = rp.scaleArray()
        if (sa[0] != sa[1] || sa[0] != sa[2]) {
            bp.syncScaleArray(sa)
        } else {
            bp.syncScale(rp.scale())
        }
    }

    /**
     * 每渲染帧刷新带寿命曲线的粒子外观。
     *
     * 只动颜色/缩放、不动位置：位置每帧改写会把桥接粒子的 xo/x 折叠成同值对，
     * 破坏原版按 partialTick 的扫掠。
     */
    fun frameSyncCurves() {
        if (curveIds.isEmpty()) return
        for (id in curveIds) {
            val rp = particles[id] ?: continue
            if (takeover.hasAppearance(id)) continue // 动画/程序直写路径自己管外观
            val bp = bridges[id] ?: continue
            syncAppearanceToBridge(rp, bp)
        }
    }

    /**
     * 客户端世界卸载（切维度/重生/退出世界）时清空全部本地状态。
     */
    fun clearAll() {
        particles.clear()
        bridges.clear()
        groups.clear()
        takeover.clearAll()
        glowingIds.clear()
        motionIds.clear()
        trackBuffers.clear()
        attachments.clear()
        curveIds.clear()
        cachedIds = emptyArray()
        cachedSize = -1
        syncCursor = 0
    }

    /**
     * 当前活跃粒子数量。
     */
    fun activeCount(): Int = particles.size

    /** 粒子是否还在；比 [snapshot] 便宜，不构造快照。 */
    fun containsParticle(id: UUID): Boolean = particles.containsKey(id)

    /** 粒子当前视觉快照（动画程序 arm 时初始化基态用）。 */
    class Snapshot(val position: Vec3, val r: Float, val g: Float, val b: Float, val a: Float, val scale: Float)

    /** 读取粒子当前视觉状态；不存在时返回 null。 */
    fun snapshot(id: UUID): Snapshot? {
        val rp = particles[id] ?: return null
        return Snapshot(rp.targetPosition(), rp.r(), rp.g(), rp.b(), rp.a(), rp.scale())
    }

    /**
     * 应用动画程序的一帧输出（客户端自驱模式）：直写渲染粒子与桥接粒子，并标记为 direct 同步。
     *
     * 首次接管时桥接位置走跳变，避免出生布局到公式布局的迁移被拉长成整 tick 的交叉扫掠。
     *
     * @param snap 这一帧强制跳变（轴心瞬移 / 断流恢复）
     */
    @JvmOverloads
    fun applyProgramFrame(id: UUID, pos: Vec3, r: Float, g: Float, b: Float, a: Float, scale: Float,
                          snap: Boolean = false) {
        val rp = particles[id] ?: return
        val firstTakeover = !takeover.hasPosition(id)
        takeover.markAppearance(id)
        trackBuffers.remove(id) // 程序接管位置，丢开 track 缓冲
        rp.setPositionDirect(pos)
        rp.setColorDirect(Color.of(r, g, b, a))
        rp.setScaleDirect(scale)
        val atomic = firstTakeover || snap
        bridges[id]?.let {
            // 首次接管与显式瞬移必须原子：位置、宽高、颜色/透明度一起落地
            it.syncPosition(pos.x, pos.y, pos.z, atomic)
            it.syncColor(r, g, b, a, atomic)
            it.syncScale(scale, atomic)
        }
    }

    /**
     * 所有发光粒子的列表（增量维护，不遍历全部粒子）。
     */
    fun getGlowingParticles(): List<RenderParticle> {
        if (glowingIds.isEmpty()) return emptyList()
        val glowing = ArrayList<RenderParticle>(glowingIds.size)
        for (id in glowingIds) {
            val p = particles[id] ?: continue
            if (p.isAlive() && p.effectiveAlpha() > 0.01f) glowing.add(p)
        }
        return glowing
    }

    companion object {
        @Volatile
        private var INSTANCE: ClientParticleEngine? = null

        /**
         * 引擎 tick 序号：每次 [frameUpdate] 自增。桥接粒子用它把「上一 tick 的外观端点」
         * 只记一次，同一 tick 内被同步多次时 prev 仍是上一 tick 的值。
         */
        @Volatile
        private var tickSequence: Long = 0L

        /** 当前引擎 tick 序号。 */
        @JvmStatic
        fun tickSequence(): Long = tickSequence

        /** 零自转向量（缺省参数复用，避免每次分配）。 */
        val ZERO_SPIN = DoubleArray(3)

        fun init() { INSTANCE = ClientParticleEngine() }
        @JvmStatic
        fun instance(): ClientParticleEngine? = INSTANCE
        @JvmStatic
        fun dispose() { INSTANCE = null }
    }
}
