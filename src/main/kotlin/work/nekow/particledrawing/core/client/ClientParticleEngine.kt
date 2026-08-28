package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.util.rotateAround
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 客户端粒子引擎，管理渲染粒子的生命周期、桥接与每帧同步。
 */
@Suppress("unused")
class ClientParticleEngine {

    private val particles: MutableMap<UUID, RenderParticle> = ConcurrentHashMap()
    private val bridges: MutableMap<UUID, BridgeParticle> = ConcurrentHashMap()
    private val groups: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap()

    // 动画本地播放直接同步的粒子：跳过 frameUpdate 的缓动轮转（避免每帧冗余插值并破坏 partialTick）
    private val directIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // 发光粒子索引：增量维护，避免 getGlowingParticles 每帧遍历全部粒子
    private val glowingIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    private var syncCursor = 0
    private var cachedIds: Array<UUID> = emptyArray()
    private var cachedSize = -1

    /**
     * 生成一个新粒子并注册到原版粒子系统中。
     * @param id 粒子唯一标识符
     * @param x 初始 X 坐标
     * @param y 初始 Y 坐标
     * @param z 初始 Z 坐标
     * @param r 红色分量
     * @param g 绿色分量
     * @param b 蓝色分量
     * @param a 透明度分量
     * @param scale 初始缩放
     * @param lifetimeTicks 存活时间（刻）
     * @param groupId 所属分组 ID，可为 null
     * @param glowing 是否发光
     */
    fun spawnParticle(id: UUID, x: Double, y: Double, z: Double,
                      r: Float, g: Float, b: Float, a: Float, scale: Float,
                      lifetimeTicks: Int, groupId: UUID?, glowing: Boolean, lightLevel: Int,
                      uv: UvData? = null) {
        if (particles.size >= ParticleDrawingConfig.CLIENT.maxRenderParticles.get()) return

        val lifetimeMs = if (lifetimeTicks > 0) lifetimeTicks * 50L else 0L
        val rp = RenderParticle(id, Vec3(x, y, z),
            Color.of(r, g, b, a), scale, glowing, lightLevel, lifetimeMs, uv)
        particles[id] = rp
        if (glowing && lightLevel > 0) glowingIds.add(id)

        val pe: ParticleEngine = Minecraft.getInstance().particleEngine
        val level = Minecraft.getInstance().level
        if (level != null) {
            val bp = BridgeParticle(id, level, x, y, z,
                Color.of(r, g, b, a), scale, glowing, uv)
            pe.add(bp)
            bridges[id] = bp
        }

        if (groupId != null) {
            groups.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(id)
        }
    }

    /**
     * 更新现有粒子的属性并设置缓动过渡目标。
     * @param id 粒子唯一标识符
     * @param x 目标 X 坐标（若 hasPos 为 true）
     * @param y 目标 Y 坐标（若 hasPos 为 true）
     * @param z 目标 Z 坐标（若 hasPos 为 true）
     * @param r 目标红色分量（若 hasColor 为 true）
     * @param g 目标绿色分量（若 hasColor 为 true）
     * @param b 目标蓝色分量（若 hasColor 为 true）
     * @param a 目标透明度分量（若 hasColor 为 true）
     * @param scale 目标缩放（若 hasScale 为 true）
     * @param hasPos 是否包含位置更新
     * @param hasColor 是否包含颜色更新
     * @param hasScale 是否包含缩放更新
     * @param durationTicks 过渡持续时间（刻）
     * @param easing 缓动类型
     */
    fun updateParticle(id: UUID, x: Double, y: Double, z: Double,
                       r: Float, g: Float, b: Float, a: Float, scale: Float,
                       hasPos: Boolean, hasColor: Boolean, hasScale: Boolean,
                       durationTicks: Int, easing: EasingType) {
        val rp = particles[id] ?: return
        directIds.remove(id)

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
                // 零时长目标立即落地（不等分批轮转），保证紧随其后的缓动包起点正确
                rp.finishColorScale()
                bridges[id]?.let {
                    it.syncColor(rp.r(), rp.g(), rp.b(), rp.a())
                    it.syncScale(rp.scale())
                }
            }
        }
    }

    /**
     * 直接、立即地同步粒子的完整可视化状态（绕过缓动状态机与分批轮转）。
     *
     * 供客户端本地动画播放使用：本地播放器每 tick 已按公式/轨道实时算出最终状态。
     * 位置用 [BridgeParticle.syncPosition] 的 snap=false 写入，保留 xo（上一 tick 位置）
     * 与 x（当前 tick 位置）的差值，让原版粒子渲染用 partialTick 在两者间平滑插值，
     * 从而按渲染帧率（而非 game tick 的 20Hz）呈现动画。
     *
     * @param id 粒子唯一标识符
     * @param x/y/z 目标位置
     * @param r/g/b/a 目标颜色
     * @param scale 目标缩放
     * @param glowing 是否发光（逐 tick 求值结果）
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
     * 直接同步粒子状态（非均匀缩放三分量版本，供动画播放使用）。
     * scaleArray [sx, sy, sz] 中 sx → quad 宽度，sy → quad 高度，sz 存储但不参与 billboard。
     */
    fun updateParticleDirectArray(id: UUID, pos: Vec3, color: Color, scaleArray: FloatArray,
                                  glowing: Boolean, lightLevel: Int, snap: Boolean = false) {
        applyDirect(id, pos, color, glowing, lightLevel, snap, 0f, scaleArray)
    }

    private fun applyDirect(id: UUID, pos: Vec3, color: Color,
                            glowing: Boolean, lightLevel: Int, snap: Boolean,
                            scale: Float, scaleArray: FloatArray?) {
        val rp = particles[id] ?: return
        directIds.add(id)
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
        }
    }

    /**
     * 设置粒子的速度向量（blocks/tick）。
     * @param id 粒子唯一标识符
     * @param vx X 速度分量
     * @param vy Y 速度分量
     * @param vz Z 速度分量
     */
    fun setVelocity(id: UUID, vx: Double, vy: Double, vz: Double) {
        directIds.remove(id)
        particles[id]?.setVelocity(Vec3(vx, vy, vz))
    }

    /**
     * 设置粒子的旋转目标（绕轴心做圆弧运动）。
     */
    fun rotateParticle(id: UUID, px: Double, py: Double, pz: Double,
                       ox: Double, oy: Double, oz: Double,
                       rx: Double, ry: Double, rz: Double,
                       durationTicks: Int, easing: EasingType) {
        directIds.remove(id)
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
        directIds.remove(id)
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
        directIds.remove(id)
        particles[id]?.setPositionSet(
            Vec3(px, py, pz), Vec3(ox, oy, oz), easing, durationTicks * 50L
        )
    }

    /**
     * 动态修改粒子的发光光照等级 (0-15)。
     * @param id 粒子唯一标识符
     * @param level 目标光照等级，自动钳制到 [0, 15]
     */
    fun setLightLevel(id: UUID, level: Int) {
        particles[id]?.setLightLevel(level)
    }

    /**
     * 销毁指定粒子并从所有分组中移除。
     * @param ids 要销毁的粒子 ID 数组
     */
    fun destroyParticles(ids: Array<UUID>) {
        for (id in ids) {
            particles.remove(id)
            bridges.remove(id)?.remove()
            directIds.remove(id)
            glowingIds.remove(id)
        }
        for (gms in groups.values) {
            for (id in ids) gms.remove(id)
        }
    }

    /**
     * 每帧更新：驱动粒子缓动并同步到桥接粒子。
     */
    fun frameUpdate() {
        syncParticlesInBatches(emptySet())

        val deadIds = ArrayList<UUID>()
        val it = particles.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.isDead()) {
                val id = entry.key
                bridges.remove(id)?.remove()
                deadIds.add(id)
                it.remove()
            }
        }
        if (deadIds.isNotEmpty()) {
            for (memberSet in groups.values) {
                for (id in deadIds) memberSet.remove(id)
            }
        }

        groups.values.removeIf { it.isEmpty() }
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
            if (rp.id() in directIds) continue

            val wasSnap = rp.consumeSnap()
            rp.tick()
            val bp = bridges[rp.id()]
            if (bp != null) {
                bp.syncPosition(rp.x(), rp.y(), rp.z(), wasSnap)
                bp.syncColor(rp.r(), rp.g(), rp.b(), rp.a())
                val sa = rp.scaleArray()
                if (sa[0] != sa[1] || sa[0] != sa[2]) {
                    bp.syncScaleArray(sa)
                } else {
                    bp.syncScale(rp.scale())
                }
            }
        }
    }

    /**
     * 获取当前活跃粒子数量。
     * @return 活跃粒子数
     */
    fun activeCount(): Int = particles.size

    /** 粒子当前视觉快照（动画程序 arm 时初始化基态用）。 */
    class Snapshot(val position: Vec3, val r: Float, val g: Float, val b: Float, val a: Float, val scale: Float)

    /** 读取粒子当前视觉状态；不存在时返回 null。 */
    fun snapshot(id: UUID): Snapshot? {
        val rp = particles[id] ?: return null
        return Snapshot(rp.targetPosition(), rp.r(), rp.g(), rp.b(), rp.a(), rp.scale())
    }

    /**
     * 应用动画程序的一帧输出（客户端自驱模式）：直写渲染粒子与桥接粒
     * 子，并标记为 direct 同步——后续分批轮转不再对其做缓动推进。
     *
     * 首次接管（该粒子此前不在 direct 同步中）时桥接位置走跳变：
     * 程序接管 = 解释权切换点，若按普通端点补间，出生布局到公式布局的
     * 迁移会被拉长为整 tick 的交叉扫掠，视觉上一瞬乱序。
     */
    fun applyProgramFrame(id: UUID, pos: Vec3, r: Float, g: Float, b: Float, a: Float, scale: Float) {
        val rp = particles[id] ?: return
        val firstTakeover = id !in directIds
        directIds.add(id)
        rp.setPositionDirect(pos)
        rp.setColorDirect(Color.of(r, g, b, a))
        rp.setScaleDirect(scale)
        bridges[id]?.let {
            it.syncPosition(pos.x, pos.y, pos.z, firstTakeover)
            it.syncColor(r, g, b, a)
            it.syncScale(scale)
        }
    }

    /**
     * 获取所有发光粒子的列表（增量维护，避免每帧遍历全部粒子）。
     * @return 发光粒子列表
     */
    fun getGlowingParticles(): List<RenderParticle> {
        if (glowingIds.isEmpty()) return emptyList()
        val glowing = ArrayList<RenderParticle>(glowingIds.size)
        for (id in glowingIds) {
            val p = particles[id] ?: continue
            if (p.isAlive() && p.a() > 0.01f) glowing.add(p)
        }
        return glowing
    }

    companion object {
        @Volatile
        private var INSTANCE: ClientParticleEngine? = null

        fun init() { INSTANCE = ClientParticleEngine() }
        @JvmStatic
        fun instance(): ClientParticleEngine? = INSTANCE
        @JvmStatic
        fun dispose() { INSTANCE = null }
    }
}
