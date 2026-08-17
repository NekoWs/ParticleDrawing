package work.nekow.particledrawing.lighting

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.BlockAndLightGetter
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.client.ClientParticleEngine
import java.util.UUID
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 动态光照管理器。
 *
 * 采用「光图坐标注入」方式实现平滑的世界方块光照：不放置任何光源方块，
 * 而是在原版渲染器查询光图坐标时（见 BrightnessGetterMixin）用带小数精度的
 * 动态光照值覆盖方块光照分量，由 GPU 的逐顶点平滑光照插值得到平滑过渡。
 *
 * 性能优化：
 *  - 空间哈希网格（cell=16）加速邻近光源查询，仅遍历周围邻近 cell；
 *  - 仅当光源移动超过阈值时才请求区块重建，并按帧限制重建数量；
 *  - 读写锁保证渲染线程与查询线程的线程安全。
 */
@Suppress("unused")
object DynamicLightManager {

    /** 空间哈希网格边长（格），与区块 section 边长一致。 */
    private const val CELL_BITS = 4
    private const val CELL_MASK = 0x1FFFFF

    /** 光源移动超过该距离平方后才会触发区块重建。 */
    private const val MOVE_THRESHOLD_SQUARED = 0.0625

    /** 每帧最大区块重建数量，避免移动大量光源时卡顿。 */
    private const val MAX_REBUILDS_PER_FRAME = 1024

    /** 光源最大照射半径（格），每帧由配置刷新，默认 16。 */
    @Volatile
    private var lightRadius = 16.0

    @Volatile
    private var lightRadiusSquared = 256.0

    /** 以 cell（16 格）为单位的邻近搜索半径，= ceil(lightRadius / 16)。 */
    @Volatile
    private var cellRange = 1

    private val LOCK = ReentrantReadWriteLock()

    /** 活跃光源列表（写锁保护）。 */
    private val sources = ArrayList<LightSource>()

    /** 空间哈希网格：cellKey -> 该 cell 内的光源（写锁保护）。 */
    private val cellIndex = HashMap<Long, MutableList<LightSource>>()

    @Volatile
    private var hasLights = false

    /** 每个光源上次烘焙时的位置（仅渲染线程访问），用于判断是否需要重建。 */
    private val lastBaked = HashMap<UUID, BakedState>()

    private class LightSource(
        val id: UUID,
        val x: Double, val y: Double, val z: Double,
        val luminance: Double
    )

    private class BakedState(val x: Double, val y: Double, val z: Double)

    /**
     * 每帧根据发光粒子刷新动态光源，并请求受影响的区块重建。
     * @param engine 客户端粒子引擎
     */
    @JvmStatic
    fun renderDynamicLights(engine: ClientParticleEngine) {
        if (!isEnabled()) {
            clear()
            return
        }

        val mc = Minecraft.getInstance()
        val player = mc.player
        if (mc.level == null || player == null) {
            clear()
            return
        }

        // 每帧刷新照射半径（由配置驱动）。
        val radius = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get().coerceAtLeast(1.0)
        lightRadius = radius
        lightRadiusSquared = radius * radius
        cellRange = ceil(radius / 16.0).toInt().coerceAtLeast(1)

        val renderDistance = mc.options.effectiveRenderDistance * 16.0
        val newSources = collectSources(engine, player.x, player.y, player.z, renderDistance)

        // 计算需要重建的区块（新增 / 移动 / 移除的光源）。
        val dirtySections = HashSet<Long>()
        val newIds = HashSet<UUID>(newSources.size)
        for (src in newSources) {
            newIds.add(src.id)
            val prev = lastBaked[src.id]
            if (prev == null) {
                markSectionsAround(dirtySections, src.x, src.y, src.z)
            } else {
                val dx = src.x - prev.x
                val dy = src.y - prev.y
                val dz = src.z - prev.z
                if (dx * dx + dy * dy + dz * dz > MOVE_THRESHOLD_SQUARED) {
                    markSectionsAround(dirtySections, prev.x, prev.y, prev.z)
                    markSectionsAround(dirtySections, src.x, src.y, src.z)
                }
            }
        }
        for ((id, prev) in lastBaked) {
            if (id !in newIds) {
                markSectionsAround(dirtySections, prev.x, prev.y, prev.z)
            }
        }

        // 重建空间索引（写锁保护）。
        LOCK.writeLock().lock()
        try {
            sources.clear()
            sources.addAll(newSources)
            cellIndex.clear()
            for (src in newSources) {
                val key = cellKey(cellCoord(src.x), cellCoord(src.y), cellCoord(src.z))
                cellIndex.getOrPut(key) { ArrayList() }.add(src)
            }
            hasLights = newSources.isNotEmpty()

            lastBaked.clear()
            for (src in newSources) {
                lastBaked[src.id] = BakedState(src.x, src.y, src.z)
            }
        } finally {
            LOCK.writeLock().unlock()
        }

        applyDirtySections(dirtySections)
    }

    /**
     * 收集本帧活跃的动态光源（按渲染距离裁剪并按亮度排序）。
     *
     * 裁剪距离取玩家当前渲染距离（view distance，单位格）：只要光源处于可被渲染的
     * 范围内就保持追踪，保证其光照在可见区块内始终正确，避免在裁剪边界处突然消失。
     */
    private fun collectSources(engine: ClientParticleEngine, camX: Double, camY: Double, camZ: Double, renderDistance: Double): List<LightSource> {
        val glowing = engine.getGlowingParticles()
        if (glowing.isEmpty()) return emptyList()

        val cullDistSq = renderDistance * renderDistance
        val maxLights = ParticleDrawingConfig.CLIENT.maxDynamicLights.get()

        // 最小堆保留亮度得分最高的 maxLights 个光源，避免对全部发光粒子做 O(N log N) 全排序
        val heap = PriorityQueue<Pair<Double, LightSource>>(compareBy { it.first })
        for (p in glowing) {
            val dx = p.x() - camX
            val dy = p.y() - camY
            val dz = p.z() - camZ
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq > cullDistSq) continue
            val luminance = p.lightLevel().toDouble()
            val score = luminance / (1.0 + sqrt(distSq))
            if (heap.size < maxLights) {
                heap.add(score to LightSource(p.id(), p.x(), p.y(), p.z(), luminance))
            } else if (score > heap.peek().first) {
                heap.poll()
                heap.add(score to LightSource(p.id(), p.x(), p.y(), p.z(), luminance))
            }
        }

        val out = ArrayList<LightSource>(heap.size)
        for ((_, src) in heap) out.add(src)
        return out
    }

    /**
     * 清空全部动态光源状态（配置关闭或离开世界时调用）。
     */
    @JvmStatic
    fun clear() {
        LOCK.writeLock().lock()
        try {
            sources.clear()
            cellIndex.clear()
            hasLights = false
        } finally {
            LOCK.writeLock().unlock()
        }
        lastBaked.clear()
    }

    /** @return 动态光照功能是否启用 */
    @JvmStatic
    fun isEnabled(): Boolean = ParticleDrawingConfig.CLIENT.enableDynamicLights.get()

    /**
     * 获取指定位置（小数坐标）的动态光照等级，返回带小数的 [Double]（0-15）。
     */
    @JvmStatic
    fun getDynamicLightLevel(x: Double, y: Double, z: Double): Double {
        if (!hasLights) return 0.0

        LOCK.readLock().lock()
        try {
            val cx = cellCoord(x)
            val cy = cellCoord(y)
            val cz = cellCoord(z)
            val range = cellRange

            var result = 0.0
            for (dx in -range..range) {
                for (dy in -range..range) {
                    for (dz in -range..range) {
                        val cell = cellIndex[cellKey(cx + dx, cy + dy, cz + dz)] ?: continue
                        for (src in cell) {
                            val ox = x - src.x
                            val oy = y - src.y
                            val oz = z - src.z
                            val distSq = ox * ox + oy * oy + oz * oz
                            if (distSq > lightRadiusSquared) continue
                            val light = src.luminance * (1.0 - sqrt(distSq) / lightRadius)
                            if (light > result) result = light
                        }
                    }
                }
            }
            return if (result > 15.0) 15.0 else result
        } finally {
            LOCK.readLock().unlock()
        }
    }

    /**
     * 将动态光照合并进打包光图坐标。
     *
     * 原版光图坐标为 `block << 4 | sky << 20`，方块光照字段的低 4 bit（bit 0-3）
     * 通常为 0。此处用 `(dynamic * 16)` 写入该字段以保留小数精度，使原版平滑光照
     * 管线（`smoothBlock` 读取 bit 0-7）在顶点间插值时获得 16 倍平滑度。
     * 仅当动态值高于原方块光照时覆盖，绝不压暗原版已照亮的区域。
     *
     * @param lightmap 原版光图坐标
     * @param x/y/z 查询位置（小数坐标）
     * @return 合并后的光图坐标
     */
    @JvmStatic
    fun getLightmapWithDynamicLight(lightmap: Int, x: Double, y: Double, z: Double): Int {
        val dynamic = getDynamicLightLevel(x, y, z)
        if (dynamic <= 0.0) return lightmap

        val block = LightCoordsUtil.block(lightmap)
        if (dynamic <= block) return lightmap

        val scaled = (dynamic * 16.0).toInt()
        // 清除方块光照字段（bit 0-19），保留天空光照字段（bit 20+）。
        return (lightmap and -0x100000) or scaled
    }

    /**
     * 供 BrightnessGetterMixin 调用：以方块位置查询并合并动态光照。
     */
    @JvmStatic
    fun getLightmapWithDynamicLight(level: BlockAndLightGetter, pos: BlockPos, lightmap: Int): Int {
        if (!isEnabled()) return lightmap
        return getLightmapWithDynamicLight(lightmap, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
    }

    /**
     * 将方块坐标映射为空间哈希 cell 坐标。
     */
    private fun cellCoord(coord: Double): Int = floor(coord).toInt() shr CELL_BITS

    /**
     * 打包 cell 坐标为哈希键（各轴 21 bit，共 63 bit）。
     */
    private fun cellKey(cx: Int, cy: Int, cz: Int): Long =
        (cx.toLong() and CELL_MASK.toLong()) or
            ((cy.toLong() and CELL_MASK.toLong()) shl 21) or
            ((cz.toLong() and CELL_MASK.toLong()) shl 42)

    /**
     * 收集指定位置周围受光照影响的区块 section 到集合中。
     */
    private fun markSectionsAround(out: MutableSet<Long>, x: Double, y: Double, z: Double) {
        val sx = SectionPos.blockToSectionCoord(floor(x).toInt())
        val sy = SectionPos.blockToSectionCoord(floor(y).toInt())
        val sz = SectionPos.blockToSectionCoord(floor(z).toInt())
        val range = cellRange
        for (dx in -range..range) {
            for (dy in -range..range) {
                for (dz in -range..range) {
                    out.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz))
                }
            }
        }
    }

    /**
     * 将待重建 section 应用到渲染器，并按帧限制重建数量。
     */
    private fun applyDirtySections(dirtySections: Set<Long>) {
        if (dirtySections.isEmpty()) return
        val mc = Minecraft.getInstance()
        if (mc.level == null) return

        val extractor = mc.levelExtractor
        var budget = MAX_REBUILDS_PER_FRAME
        for (packed in dirtySections) {
            if (budget-- <= 0) break
            extractor.setSectionDirty(SectionPos.x(packed), SectionPos.y(packed), SectionPos.z(packed))
        }
    }
}
