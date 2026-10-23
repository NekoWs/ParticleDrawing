package work.nekow.particledrawing.lighting

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.BlockAndLightGetter
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.client.ClientParticleEngine
import java.util.LinkedHashSet
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** 光图写入精度是 1/16 级：签名、烘焙脏判与缓存失效都按这个粒度比较。 */
private fun quantizeLuminance(luminance: Double): Int = (luminance * 16.0).toInt()

/**
 * 地形烘焙的脏判策略。
 *
 * [base*] 是「已请求/完成重建」的基准，[last*] 是上一渲染帧观察到的位置。小位移先累计在
 * 基准上，超过阈值才重建；光源停住时即使只差一点也补一次，保证最终地形与当前光场一致。
 */
internal object LightBakePolicy {

    /** 光源相对基准移动超过该距离平方才重建；0.25 格。 */
    const val MOVE_THRESHOLD_SQUARED = 0.0625

    /** 上一帧位移小于该距离平方视为已停住。 */
    private const val SETTLE_EPSILON_SQUARED = 1e-12

    fun shouldRebake(
        baseX: Double, baseY: Double, baseZ: Double, baseLightQ: Int,
        lastX: Double, lastY: Double, lastZ: Double,
        x: Double, y: Double, z: Double, lightQ: Int,
    ): Boolean {
        val dx = x - baseX
        val dy = y - baseY
        val dz = z - baseZ
        val movedSq = dx * dx + dy * dy + dz * dz
        if (movedSq <= 0.0) return lightQ != baseLightQ

        val lastDx = x - lastX
        val lastDy = y - lastY
        val lastDz = z - lastZ
        val settled = lastDx * lastDx + lastDy * lastDy + lastDz * lastDz <= SETTLE_EPSILON_SQUARED
        return movedSq > MOVE_THRESHOLD_SQUARED || lightQ != baseLightQ || settled
    }
}

// 动态光照管理器：不放置光源方块，把带小数精度的光照值写进光图坐标，靠 GPU 逐顶点插值得到平滑过渡。
// 空间哈希（cell=16 格）加速邻近查询；位移在「已请求重建」的基准上累计，脏 section 跨帧排队，
// 预算用完不丢请求；读写锁保证线程安全。
@Suppress("unused")
object DynamicLightManager {

    /** 空间哈希网格边长（格），与区块 section 边长一致。 */
    private const val CELL_BITS = 4
    private const val CELL_MASK = 0x1FFFFF

    /** 每帧最大区块重建数量。 */
    private const val MAX_REBUILDS_PER_FRAME = 1024

    /** 光源最大照射半径（格），每帧从配置刷新，默认 16。 */
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

    /**
     * 动态光照版本号：光源集合、位置、亮度或开关有任何变化就自增。
     *
     * 桥接粒子的光照缓存按它失效，缓存的是方块光叠加动态光之后的结果，
     * 光源挪走、销毁或关掉时若不失效，静止的非发光粒子会一直亮着旧值。
     */
    @Volatile
    private var version: Int = 0

    /** 上一次的轻量签名：与 [version] 一起用来判断「这一帧的光场变了没有」。 */
    private var lastSignature: Long = 0L
    private var lastEnabled: Boolean = false

    /**
     * 每个光源「已请求/完成地形重建」的基准（仅渲染线程访问）。
     *
     * 不是上一帧位置：小位移先累计在这里，超过阈值或光源停住时才更新，
     * 否则每帧重写基准会让 0.1 格/t 的慢移永远够不到重建阈值。
     */
    private val lastBaked = HashMap<UUID, BakedState>()

    /** 已排队、还没提交给渲染器的脏 section；跨帧保留，预算用完不丢。 */
    private val pendingSections = LinkedHashSet<Long>()

    /** [pendingSections] 所属的关卡；换维后旧 section 键不能带到新世界。 */
    private var pendingLevel: ClientLevel? = null

    internal class LightSource(
        val id: UUID,
        val x: Double, val y: Double, val z: Double,
        val luminance: Double
    ) {
        /** 光图写入精度是 1/16 级；签名与烘焙比较都按这个精度量化。 */
        val lightQ: Int get() = quantizeLuminance(luminance)
    }

    private class BakedState(
        val x: Double, val y: Double, val z: Double, val lightQ: Int,
        var lastX: Double = x, var lastY: Double = y, var lastZ: Double = z,
    )

    /**
     * 每帧根据发光粒子刷新动态光源，并请求受影响的区块重建。
     *
     * @param engine 客户端粒子引擎
     * @param partialTick 渲染帧在本 tick 内的进度；0 = 取本 tick 的权威外观（客户端 tick 观察用）。
     *   渲染路径要传当前 partialTick，让光源位置、光强与画面处在同一帧。
     */
    @JvmStatic
    @JvmOverloads
    fun renderDynamicLights(engine: ClientParticleEngine, partialTick: Float = 0f) {
        if (!isEnabled()) {
            clear()
            applyPendingSections()
            return
        }

        val mc = Minecraft.getInstance()
        val player = mc.player
        val level = mc.level
        if (level == null || player == null) {
            clear()
            applyPendingSections()
            return
        }
        // 先切 pending 的关卡归属，再产生本帧脏 section，避免刚入队就被换维清掉
        ensurePendingLevel(level)

        // 每帧刷新照射半径，由配置驱动；半径变了要把旧、新影响范围都失效。
        val radius = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get().coerceAtLeast(1.0)
        val newRange = ceil(radius / 16.0).toInt().coerceAtLeast(1)
        val oldRange = cellRange
        val radiusChanged = radius != lightRadius || newRange != oldRange
        lightRadius = radius
        lightRadiusSquared = radius * radius
        cellRange = newRange

        val renderDistance = mc.options.effectiveRenderDistance * 16.0
        val newSources = collectSources(engine, player.x, player.y, player.z, renderDistance, partialTick)

        // 光场变了就抬版本号：位置、亮度、数量或照射半径任一变化都算
        var signature = signatureOf(newSources)
        signature = signature * 31 + java.lang.Double.doubleToLongBits(radius)
        if (signature != lastSignature || !lastEnabled) {
            lastSignature = signature
            lastEnabled = true
            version++
        }

        // 计算需要重建的区块：新增、移动、移除的光源。
        val dirtySections = LinkedHashSet<Long>()
        if (radiusChanged) {
            val range = max(oldRange, newRange)
            for (prev in lastBaked.values) {
                markSectionsAround(dirtySections, prev.x, prev.y, prev.z, range)
            }
            for (src in newSources) {
                markSectionsAround(dirtySections, src.x, src.y, src.z, range)
            }
        }

        val nextBaked = HashMap<UUID, BakedState>(newSources.size)
        val newIds = HashSet<UUID>(newSources.size)
        for (src in newSources) {
            newIds.add(src.id)
            val prev = lastBaked[src.id]
            if (prev == null) {
                markSectionsAround(dirtySections, src.x, src.y, src.z)
                nextBaked[src.id] = BakedState(src.x, src.y, src.z, src.lightQ)
                continue
            }
            if (LightBakePolicy.shouldRebake(
                    prev.x, prev.y, prev.z, prev.lightQ,
                    prev.lastX, prev.lastY, prev.lastZ,
                    src.x, src.y, src.z, src.lightQ,
                )
            ) {
                markSectionsAround(dirtySections, prev.x, prev.y, prev.z)
                markSectionsAround(dirtySections, src.x, src.y, src.z)
                nextBaked[src.id] = BakedState(src.x, src.y, src.z, src.lightQ)
            } else {
                // 小位移继续累计在基准上，只记下这一帧看到的位置
                prev.lastX = src.x
                prev.lastY = src.y
                prev.lastZ = src.z
                nextBaked[src.id] = prev
            }
        }
        for ((id, prev) in lastBaked) {
            if (id !in newIds) {
                // 基准可能落后于最后看到的位置（小位移还没触发重建），两处都失效
                markSectionsAround(dirtySections, prev.x, prev.y, prev.z)
                markSectionsAround(dirtySections, prev.lastX, prev.lastY, prev.lastZ)
            }
        }

        // 先排队再更新基准：预算不够时请求留在 pending 里，不会因基准提前更新而丢
        pendingSections.addAll(dirtySections)

        // 重建空间索引，写锁保护。
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
            lastBaked.putAll(nextBaked)
        } finally {
            LOCK.writeLock().unlock()
        }

        applyPendingSections()
    }

    /**
     * 收集本帧活跃的动态光源：按渲染距离裁剪、按 cell 分区限数，位置按 partialTick 插值。
     *
     * 每个 16×16×16 cell 内只保留亮度得分最高的 [maxDynamicLightsPerCell] 个光源，
     * 再用全局上限兜底。
     */
    private fun collectSources(engine: ClientParticleEngine, camX: Double, camY: Double, camZ: Double, renderDistance: Double, partialTick: Float): List<LightSource> {
        val glowing = engine.getGlowingParticles()
        if (glowing.isEmpty()) return emptyList()

        val cullDistSq = renderDistance * renderDistance
        val perCell = ParticleDrawingConfig.CLIENT.maxDynamicLightsPerCell.get().coerceAtLeast(1)

        fun lightScore(dx: Double, dy: Double, dz: Double, luminance: Double): Double =
            luminance / (1.0 + sqrt(dx * dx + dy * dy + dz * dz))

        // 每个 cell 一个最小堆，保留该 cell 内亮度最高的 perCell 个光源
        val cellHeaps = HashMap<Long, PriorityQueue<Pair<Double, LightSource>>>()
        for (p in glowing) {
            val x = p.interpolatedX(partialTick)
            val y = p.interpolatedY(partialTick)
            val z = p.interpolatedZ(partialTick)
            val dx = x - camX
            val dy = y - camY
            val dz = z - camZ
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq > cullDistSq) continue
            // 光强按本帧的分数年龄采样：寿命曲线、透明度、尺寸包络与运行时目标都在这里合流。
            // 光图最高 15 级，乘数大于 1 时只影响裁剪排序，最终仍封顶。
            val luminance = p.lightIntensity(partialTick).toDouble().coerceAtMost(15.0)
            if (luminance <= 0.0) continue
            val score = lightScore(dx, dy, dz, luminance)
            val src = LightSource(p.id(), x, y, z, luminance)
            val key = cellKey(cellCoord(x), cellCoord(y), cellCoord(z))
            // 同分时按 id 定序：光源集合的遍历顺序抖动不应让预算裁剪在相邻帧换人
            val heap = cellHeaps.getOrPut(key) {
                PriorityQueue(compareBy<Pair<Double, LightSource>> { it.first }.thenBy { it.second.id })
            }
            if (heap.size < perCell) {
                heap.add(score to src)
            } else if (score > heap.peek().first) {
                heap.poll()
                heap.add(score to src)
            }
        }

        val out = ArrayList<LightSource>()
        for (heap in cellHeaps.values) for ((_, src) in heap) out.add(src)

        // 全局上限兜底：超出时按亮度得分裁剪
        val maxLights = ParticleDrawingConfig.CLIENT.maxDynamicLights.get()
        if (out.size > maxLights) {
            val heap = PriorityQueue<Pair<Double, LightSource>>(
                compareBy<Pair<Double, LightSource>> { it.first }.thenBy { it.second.id },
            )
            for (src in out) {
                val dx = src.x - camX
                val dy = src.y - camY
                val dz = src.z - camZ
                val score = lightScore(dx, dy, dz, src.luminance)
                if (heap.size < maxLights) {
                    heap.add(score to src)
                } else if (score > heap.peek().first) {
                    heap.poll()
                    heap.add(score to src)
                }
            }
            val capped = ArrayList<LightSource>(heap.size)
            for ((_, src) in heap) capped.add(src)
            return capped
        }
        return out
    }

    /**
     * 清空全部动态光源状态（配置关闭或离开世界时调用）。
     */
    @JvmStatic
    fun clear() {
        val oldSources = ArrayList<LightSource>()
        LOCK.writeLock().lock()
        try {
            oldSources.addAll(sources)
            sources.clear()
            cellIndex.clear()
            hasLights = false
        } finally {
            LOCK.writeLock().unlock()
        }

        // 光源没了也要把旧受光地形排进重建队列，否则关掉动态光后地形还亮着
        for (src in oldSources) {
            markSectionsAround(pendingSections, src.x, src.y, src.z)
        }
        for (prev in lastBaked.values) {
            markSectionsAround(pendingSections, prev.x, prev.y, prev.z)
        }
        lastBaked.clear()

        // 光源全没了也算光场变了：桥接粒子必须重新采样，否则会亮着旧值
        if (lastEnabled || lastSignature != 0L) {
            lastEnabled = false
            lastSignature = 0L
            version++
        }
    }

    /**
     * 当前动态光照版本（每次光场变化自增）。桥接粒子用它决定光照缓存是否还有效。
     */
    @JvmStatic
    fun version(): Int = version

    /**
     * 光源列表的轻量签名：数量、身份、位置与量化亮度都计入。
     *
     * 亮度按光图写入精度（1/16 级）量化，既不会把小数亮度截断成整数，也不会因为
     * 低于可见精度的抖动每帧抬版本号。各元素贡献相加，与遍历顺序无关。
     */
    internal fun signatureOf(list: List<LightSource>): Long {
        var h = 1125899906842597L + list.size
        for (src in list) {
            var e = src.id.hashCode().toLong()
            e = e * 31 + java.lang.Double.doubleToLongBits(src.x + src.y * 3.0 + src.z * 7.0)
            e = e * 31 + src.lightQ
            h += e * -7046029254386353131L
        }
        return h
    }

    /** @return 动态光照是否启用 */
    @JvmStatic
    fun isEnabled(): Boolean = ParticleDrawingConfig.CLIENT.enableDynamicLights.get()

    /**
     * 查询指定位置（小数坐标）的动态光照等级，返回带小数的 [Double]（0-15）。
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
     * 仅当动态值高于原方块光照时覆盖，不压暗原版已照亮的区域。
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
     * 以方块位置查询并合并动态光照，供亮度获取器混入调用。
     */
    @JvmStatic
    fun getLightmapWithDynamicLight(level: BlockAndLightGetter, pos: BlockPos, lightmap: Int): Int {
        if (!isEnabled()) return lightmap
        return getLightmapWithDynamicLight(lightmap, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
    }

    /**
     * 把方块坐标映射为空间哈希的 cell 坐标。
     */
    private fun cellCoord(coord: Double): Int = floor(coord).toInt() shr CELL_BITS

    /**
     * 把 cell 坐标打包成哈希键（各轴 21 bit，共 63 bit）。
     */
    private fun cellKey(cx: Int, cy: Int, cz: Int): Long =
        (cx.toLong() and CELL_MASK.toLong()) or
            ((cy.toLong() and CELL_MASK.toLong()) shl 21) or
            ((cz.toLong() and CELL_MASK.toLong()) shl 42)

    /**
     * 把指定位置周围受光照影响的区块 section 收进集合。
     */
    private fun markSectionsAround(
        out: MutableSet<Long>,
        x: Double, y: Double, z: Double,
        range: Int = cellRange,
    ) {
        val sx = SectionPos.blockToSectionCoord(floor(x).toInt())
        val sy = SectionPos.blockToSectionCoord(floor(y).toInt())
        val sz = SectionPos.blockToSectionCoord(floor(z).toInt())
        for (dx in -range..range) {
            for (dy in -range..range) {
                for (dz in -range..range) {
                    out.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz))
                }
            }
        }
    }

    /** 切关卡时丢掉上一关的 pending 键与基准；同一关则保留跨帧积压。 */
    private fun ensurePendingLevel(level: ClientLevel) {
        val previous = pendingLevel
        if (previous != null && previous !== level) {
            pendingSections.clear()
            lastBaked.clear()
        }
        pendingLevel = level
    }

    /**
     * 把排队的脏 section 交给渲染器，并按帧限流。
     *
     * 没提交的条目留在 [pendingSections] 里，下一帧继续；预算耗尽的帧不会把请求丢掉。
     * 换关卡时旧 section 键作废，避免在新世界重建一批无关区块。
     */
    private fun applyPendingSections() {
        if (pendingSections.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) {
            pendingSections.clear()
            pendingLevel = null
            return
        }
        val previous = pendingLevel
        pendingLevel = level
        if (previous != null && previous !== level) {
            pendingSections.clear()
            return
        }

        val extractor = mc.levelExtractor
        var budget = MAX_REBUILDS_PER_FRAME
        val it = pendingSections.iterator()
        while (it.hasNext() && budget > 0) {
            val packed = it.next()
            extractor.setSectionDirty(SectionPos.x(packed), SectionPos.y(packed), SectionPos.z(packed))
            it.remove()
            budget--
        }
    }
}
