package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.motion.MotionSystem
import work.nekow.particledrawing.core.motion.rotateAround
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

    private var syncCursor = 0
    private var cachedIds: Array<UUID> = emptyArray()
    private var cachedSize = -1

    /**
     * 生成一个新粒子并注册到原版粒子系统中。
     * @param id 粒子唯一标识符
     * @param style 粒子样式
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
    fun spawnParticle(id: UUID, style: ParticleStyle, x: Double, y: Double, z: Double,
                      r: Float, g: Float, b: Float, a: Float, scale: Float,
                      lifetimeTicks: Int, groupId: UUID?, glowing: Boolean, lightLevel: Int) {
        if (particles.size >= ParticleDrawingConfig.CLIENT.maxRenderParticles.get()) return

        val lifetimeMs = if (lifetimeTicks > 0) lifetimeTicks * 50L else 0L
        val rp = RenderParticle(id, style, Vec3(x, y, z),
            Color.of(r, g, b, a), scale, glowing, lightLevel, lifetimeMs)
        particles[id] = rp

        val pe: ParticleEngine = Minecraft.getInstance().particleEngine
        val level = Minecraft.getInstance().level
        if (level != null) {
            val bp = BridgeParticle(id, style, level, x, y, z,
                Color.of(r, g, b, a), scale, glowing)
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
        particles[id]?.setVelocity(Vec3(vx, vy, vz))
    }

    /**
     * 设置粒子的旋转目标（绕轴心做圆弧运动）。
     */
    fun rotateParticle(id: UUID, px: Double, py: Double, pz: Double,
                       ox: Double, oy: Double, oz: Double,
                       rx: Double, ry: Double, rz: Double,
                       durationTicks: Int, easing: EasingType) {
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
        }
        for (gms in groups.values) {
            for (id in ids) gms.remove(id)
        }
    }

    /**
     * 对分组中的所有粒子应用统一变换。
     * @param groupId 分组 ID
     * @param transformType 变换类型：0=位移, 1=旋转, 2=颜色, 3=缩放
     * @param dx X 位移量
     * @param dy Y 位移量
     * @param dz Z 位移量
     * @param ax 旋转轴 X 分量
     * @param ay 旋转轴 Y 分量
     * @param az 旋转轴 Z 分量
     * @param radians 旋转弧度
     * @param r 目标红色分量
     * @param g 目标绿色分量
     * @param b 目标蓝色分量
     * @param a 目标透明度分量
     * @param targetScale 目标缩放
     * @param px 旋转/缩放基准点 X
     * @param py 旋转/缩放基准点 Y
     * @param pz 旋转/缩放基准点 Z
     * @param durationTicks 过渡持续时间（刻）
     * @param easing 缓动类型
     */
    fun applyGroupTransform(groupId: UUID, transformType: Int,
                            dx: Double, dy: Double, dz: Double,
                            ax: Double, ay: Double, az: Double, radians: Double,
                            r: Float, g: Float, b: Float, a: Float,
                            targetScale: Float, px: Double, py: Double, pz: Double,
                            durationTicks: Int, easing: EasingType) {
        val members = groups[groupId] ?: return

        val pivot = Vec3(px, py, pz)
        val durationMs = durationTicks * 50L

        for (memberId in members) {
            val rp = particles[memberId] ?: continue

            // 使用目标位置计算变换
            val curPos = rp.targetPosition()
            val newPos: Vec3
            val newColor: Color
            val newScale: Float

            when (transformType) {
                0 -> {
                    newPos = curPos.add(dx, dy, dz)
                    newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a())
                    newScale = rp.scale()
                }
                1 -> {
                    val rel = curPos.subtract(pivot)
                    val axis = Vec3(ax, ay, az).normalize()
                    val rotated = rel.rotateAround(axis, radians)
                    newPos = pivot.add(rotated)
                    newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a())
                    newScale = rp.scale()
                }
                2 -> {
                    newPos = curPos
                    newColor = Color.of(r, g, b, a)
                    newScale = rp.scale()
                }
                3 -> {
                    val rel = curPos.subtract(pivot)
                    newPos = pivot.add(rel.scale(targetScale.toDouble()))
                    newScale = targetScale
                    newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a())
                }
                else -> continue
            }

            rp.setTarget(newPos, newColor, newScale, easing, durationMs)
        }
    }

    /**
     * 每帧更新：驱动粒子缓动并同步到桥接粒子。
     */
    fun frameUpdate() {
        MotionSystem.tick(groups, particles, bridges)
        val motionParticles = motionParticleIds()

        syncParticlesInBatches(motionParticles)

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

            val wasSnap = rp.consumeSnap()
            rp.tick()
            val bp = bridges[rp.id()]
            if (bp != null) {
                bp.syncPosition(rp.x(), rp.y(), rp.z(), wasSnap)
                bp.syncColor(rp.r(), rp.g(), rp.b(), rp.a())
                bp.syncScale(rp.scale())
            }
        }
    }

    /**
     * 获取当前活跃粒子数量。
     * @return 活跃粒子数
     */
    fun activeCount(): Int = particles.size

    /**
     * 获取所有发光粒子的列表。
     * @return 发光粒子列表
     */
    fun getGlowingParticles(): List<RenderParticle> {
        val glowing = ArrayList<RenderParticle>()
        for (p in particles.values) {
            if (p.glowing() && p.lightLevel() > 0 && p.isAlive() && p.a() > 0.01f) {
                glowing.add(p)
            }
        }
        return glowing
    }

    // --- 运动系统委托 ---

    /** 收集所有处于运动算法控制下的粒子 ID。 */
    private fun motionParticleIds(): Set<UUID> {
        val ids = HashSet<UUID>()
        for (gid in MotionSystem.activeGroupIds()) {
            groups[gid]?.let { ids.addAll(it) }
        }
        return ids
    }

    fun addMotion(groupId: UUID, active: Boolean, algorithmId: String,
                  params: DoubleArray, px: Double, py: Double, pz: Double) {
        if (active) {
            val pivot = Vec3(px, py, pz)
            val snapshot = mutableMapOf<UUID, Vec3>()
            groups[groupId]?.forEach { id ->
                particles[id]?.targetPosition()?.let { snapshot[id] = it }
            }
            MotionSystem.start(groupId, algorithmId, params, pivot, snapshot)
        } else {
            MotionSystem.stop(groupId)
        }
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
