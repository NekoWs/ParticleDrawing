package work.nekow.particledrawing.lighting

import work.nekow.particledrawing.platform.PlatformCompat
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.SectionPos
import work.nekow.particledrawing.api.LightSample
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.light.LightMath
import work.nekow.particledrawing.light.LightShape
import work.nekow.particledrawing.core.client.ClientParticleEngine
import work.nekow.particledrawing.core.client.RenderParticle
import java.util.LinkedHashSet
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.roundToInt

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
object DynamicLightManager {

    /** 空间哈希网格边长（格），与区块 section 边长一致。 */
    private const val CELL_BITS = 4
    private const val CELL_MASK = 0x1FFFFF

    /** 地形批次每 50ms 提交一次；光束、实体和粒子取光仍逐帧更新。 */
    private const val TERRAIN_REFRESH_NANOS = 50_000_000L
    private const val MAX_REBUILDS_PER_BATCH = 1024

    private val TRIPLE_ZERO = Triple(0.0, 0.0, 0.0)

    /** 光源最大照射半径（格），每帧从配置刷新，默认 16。 */
    private const val DEFAULT_RADIUS = 16.0
    private const val TERRAIN_SAMPLE_RADIUS = 0.85

    /** 以 cell（16 格）为单位的邻近搜索半径，= ceil(lightRadius / 16)。 */
    @Volatile
    private var cellRange = 1

    private val LOCK = ReentrantReadWriteLock()

    /** 活跃光源列表（写锁保护）。 */
    private val sources = ArrayList<LightSource>()

    /** 空间哈希网格：cellKey -> 该 cell 内的光源（写锁保护）。 */
    private val cellIndex = HashMap<Long, MutableList<LightSource>>()

    @Volatile
    private var lightBounds: LightBounds? = null

    private class LightBounds(
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double,
    ) {
        fun contains(x: Double, y: Double, z: Double, margin: Double = 0.0): Boolean =
            x >= minX - margin && x <= maxX + margin &&
                y >= minY - margin && y <= maxY + margin &&
                z >= minZ - margin && z <= maxZ + margin
    }

    /** 光源数量快照（写锁内更新），诊断日志读它，避免无锁读可变列表。 */
    @Volatile
    private var sourceCountSnapshot = 0

    /** 最近一帧收集到的光源与照射半径：对外只读快照读它，避免读可变列表。 */
    @Volatile
    private var frameSources: Array<LightSource> = emptyArray()

    /**
     * 最近一帧动态光源的只读快照；没有光源或功能关闭时是空列表。
     *
     * 供第三方按自己的预算筛选取用（例如接入光影包），不依赖粒子引擎对象。
     */
    fun lightSnapshot(): List<LightSample> {
        val snapshot = frameSources
        if (snapshot.isEmpty()) return emptyList()
        val out = ArrayList<LightSample>(snapshot.size)
        toSamples(snapshot, out)
        return out
    }

    /** 把快照写进 [out]（先清空），返回数量；需要逐帧取用又不建列表时用它。 */
    fun snapshotInto(out: MutableList<LightSample>): Int {
        out.clear()
        toSamples(frameSources, out)
        return out.size
    }

    /** 光源 -> 快照项；映射本身无状态，单测直接喂构造好的光源。 */
    internal fun toSamples(sources: Array<LightSource>, out: MutableList<LightSample>) {
        for (src in sources) out += LightSample(src.id, src.x, src.y, src.z, src.luminance, src.range, src.shape, src.rgb)
    }

    /** 当前生效的动态光源数量（诊断用，精确到上一次重建）。 */
    @JvmStatic
    fun sourceCount(): Int = sourceCountSnapshot

    private val terrainOverrides = java.util.concurrent.atomic.LongAdder()
    @Volatile
    private var sectionsMarked = 0L

    @JvmStatic
    fun diagnostics(): String =
        "地形光图覆盖=${terrainOverrides.sum()} 脏区块提交=$sectionsMarked"

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

    /** 已排队的旧、新覆盖区；跨帧合并，防止快速扫过时遗漏要清掉的光斑。 */
    private val pendingSections = LinkedHashSet<Long>()

    /** [pendingSections] 所属的关卡；换维后旧 section 键不能带到新世界。 */
    private var pendingLevel: ClientLevel? = null
    private var lastTerrainSubmitNs: Long? = null

    internal class LightSource(
        val id: UUID,
        val x: Double, val y: Double, val z: Double,
        val luminance: Double,
        /** 这盏灯实际生效的照射半径（已按全局上限夹过）。 */
        val range: Double = DEFAULT_RADIUS,
        val dirX: Double = 0.0, val dirY: Double = 0.0, val dirZ: Double = 0.0,
        val innerCos: Double = 1.0, val outerCos: Double = -1.0,
        val edge: Double = 1.0,
        val falloff: LightFalloff = LightFalloff.LINEAR,
        val shape: LightShape? = null,
        val rgb: Int = 0xffffff,
    ) {
        /** 光图写入精度是 1/16 级；签名与烘焙比较都按这个精度量化。 */
        val lightQ: Int get() = quantizeLuminance(luminance)
    }

    private class BakedState(
        val x: Double, val y: Double, val z: Double, val lightQ: Int,
        val range: Double, val shape: LightShape?, val rgb: Int,
        var lastX: Double = x, var lastY: Double = y, var lastZ: Double = z,
    )

    /** 裁剪用的候选：分数用 double 存，不装箱；比 `Pair<Double, LightSource>` 少两次分配。 */
    private class Candidate(val score: Double, val src: LightSource)

    /** 候选的定序（先分数，同分按 id）：建一次复用，不逐 cell 逐帧新建比较器。 */
    private val CANDIDATE_ORDER: Comparator<Candidate> = Comparator { a, b ->
        val byScore = a.score.compareTo(b.score)
        if (byScore != 0) byScore else a.src.id.compareTo(b.src.id)
    }

    /** 本帧的发光粒子候选：复用同一份，不逐帧新建列表。 */
    private val candidates = ArrayList<RenderParticle>()

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

        // 没有发光粒子、也没有上一帧留下的光源和待重建区块：整段跳过。
        // 省掉配置读取、关卡查询和每帧的光源收集，没有光效的场面（占大多数）不再付这份成本。
        // 最后一个光源消失的那一帧 sources 仍非空，会正常走完重建并把版本号抬起来。
        if (pendingSections.isEmpty() && sources.isEmpty() && !engine.hasLightEmitters()) return

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

        val radius = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get().coerceIn(1.0, LightShape.MAX_RANGE)
        val renderDistance = mc.options.effectiveRenderDistance * 16.0
        val newSources = collectSources(engine, player.x, player.y, player.z, renderDistance, partialTick, radius)
        val range = newSources.maxOfOrNull { it.range } ?: radius
        val newCellRange = ceil(range / 16.0).toInt()

        // 光场变了就抬版本号：位置、亮度、数量或照射半径任一变化都算
        val signature = signatureOf(newSources)
        val changed = signature != lastSignature || !lastEnabled

        // 计算需要重建的区块：新增、移动、移除的光源。
        val dirtySections = LinkedHashSet<Long>()
        val nextBaked = HashMap<UUID, BakedState>(newSources.size)
        for (src in newSources) {
            val prev = lastBaked[src.id]
            if (prev == null) {
                markSectionsAround(dirtySections, src.x, src.y, src.z, src.range)
                nextBaked[src.id] = BakedState(src.x, src.y, src.z, src.lightQ, src.range, src.shape, src.rgb)
                continue
            }
            if (prev.range != src.range || prev.shape != src.shape || prev.rgb != src.rgb || LightBakePolicy.shouldRebake(
                    prev.x, prev.y, prev.z, prev.lightQ,
                    prev.lastX, prev.lastY, prev.lastZ,
                    src.x, src.y, src.z, src.lightQ,
                )
            ) {
                markSectionsAround(dirtySections, prev.x, prev.y, prev.z, prev.range)
                markSectionsAround(dirtySections, src.x, src.y, src.z, src.range)
                nextBaked[src.id] = BakedState(src.x, src.y, src.z, src.lightQ, src.range, src.shape, src.rgb)
            } else {
                // 小位移继续累计在基准上，只记下这一帧看到的位置
                prev.lastX = src.x
                prev.lastY = src.y
                prev.lastZ = src.z
                nextBaked[src.id] = prev
            }
        }
        // nextBaked 收齐了本帧全部光源，直接拿它当「还在不在」的集合，不再另建一份 id 表
        for ((id, prev) in lastBaked) {
            if (!nextBaked.containsKey(id)) {
                // 基准可能落后于最后看到的位置（小位移还没触发重建），两处都失效
                markSectionsAround(dirtySections, prev.x, prev.y, prev.z, prev.range)
                markSectionsAround(dirtySections, prev.lastX, prev.lastY, prev.lastZ, prev.range)
            }
        }

        // 先排队再更新基准：预算不够时请求留在 pending 里，不会因基准提前更新而丢
        pendingSections.addAll(dirtySections)

        lastBaked.clear()
        lastBaked.putAll(nextBaked)
        if (changed) {
            LOCK.writeLock().lock()
            try {
                sources.clear()
                sources.addAll(newSources)
                cellIndex.clear()
                cellRange = newCellRange
                for (src in newSources) {
                    val key = cellKey(cellCoord(src.x), cellCoord(src.y), cellCoord(src.z))
                    cellIndex.getOrPut(key) { ArrayList() }.add(src)
                }
                frameSources = newSources.toTypedArray()
                sourceCountSnapshot = newSources.size
                lightBounds = if (newSources.isEmpty()) null else LightBounds(
                    newSources.minOf { it.x - it.range },
                    newSources.minOf { it.y - it.range },
                    newSources.minOf { it.z - it.range },
                    newSources.maxOf { it.x + it.range },
                    newSources.maxOf { it.y + it.range },
                    newSources.maxOf { it.z + it.range },
                )
                lastSignature = signature
                lastEnabled = true
                version++
            } finally {
                LOCK.writeLock().unlock()
            }
        }

        applyPendingSections()
    }

    /**
     * 收集本帧活跃的动态光源：按渲染距离裁剪、按 cell 分区限数，位置按 partialTick 插值。
     *
     * 每个 16×16×16 cell 内只保留亮度得分最高的 [maxDynamicLightsPerCell] 个光源，
     * 再用全局上限兜底。
     */
    private fun collectSources(engine: ClientParticleEngine, camX: Double, camY: Double, camZ: Double, renderDistance: Double, partialTick: Float, defaultRadius: Double): List<LightSource> {
        engine.lightEmittersInto(candidates)
        val maxLights = ParticleDrawingConfig.CLIENT.maxDynamicLights.get()
        if (candidates.isEmpty() || maxLights == 0) return emptyList()

        val cullDistSq = renderDistance * renderDistance
        val perCell = ParticleDrawingConfig.CLIENT.maxDynamicLightsPerCell.get().coerceAtLeast(1)

        fun lightScore(dx: Double, dy: Double, dz: Double, luminance: Double): Double =
            luminance / (1.0 + sqrt(dx * dx + dy * dy + dz * dz))

        // 每个 cell 一个最小堆，保留该 cell 内亮度最高的 perCell 个光源
        val cellHeaps = HashMap<Long, PriorityQueue<Candidate>>()
        for (p in candidates) {
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
            val key = cellKey(cellCoord(x), cellCoord(y), cellCoord(z))
            // 形状是每颗粒子自带的外观参数：射灯给出锥角与朝向，点光拿到的是零向量。
            val shape = p.shape(partialTick)
            val spot = shape != null && shape.isSpot
            val dir = if (spot) shape.direction() else TRIPLE_ZERO
            val src = LightSource(
                p.id(), x, y, z, luminance,
                range = (shape?.range?.takeIf { it > 0.0 } ?: defaultRadius),
                dirX = dir.first, dirY = dir.second, dirZ = dir.third,
                innerCos = if (spot) shape.innerCos() else 1.0,
                outerCos = if (spot) shape.outerCos() else -1.0,
                edge = shape?.edge ?: 1.0,
                falloff = shape?.falloff ?: LightFalloff.LINEAR,
                shape = shape,
                rgb = p.lightColor(partialTick),
            )
            // 同分时按 id 定序：光源集合的遍历顺序抖动不应让预算裁剪在相邻帧换人
            val heap = cellHeaps.getOrPut(key) { PriorityQueue(CANDIDATE_ORDER) }
            val candidate = Candidate(score, src)
            if (heap.size < perCell || CANDIDATE_ORDER.compare(candidate, heap.peek()) > 0) {
                if (heap.size == perCell) heap.poll()
                heap.add(candidate)
            }
        }

        val out = ArrayList<LightSource>()
        for (heap in cellHeaps.values) for (c in heap) out.add(c.src)

        // 全局上限兜底：超出时按亮度得分裁剪
        if (out.size > maxLights) {
            val heap = PriorityQueue(CANDIDATE_ORDER)
            for (src in out) {
                val dx = src.x - camX
                val dy = src.y - camY
                val dz = src.z - camZ
                val score = lightScore(dx, dy, dz, src.luminance)
                val candidate = Candidate(score, src)
                if (heap.size < maxLights || CANDIDATE_ORDER.compare(candidate, heap.peek()) > 0) {
                    if (heap.size == maxLights) heap.poll()
                    heap.add(candidate)
                }
            }
            val capped = ArrayList<LightSource>(heap.size)
            for (c in heap) capped.add(c.src)
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
            lightBounds = null
            sourceCountSnapshot = 0
            frameSources = emptyArray()
        } finally {
            LOCK.writeLock().unlock()
        }

        // 光源没了也要把旧受光地形排进重建队列，否则关掉动态光后地形还亮着
        for (src in oldSources) {
            markSectionsAround(pendingSections, src.x, src.y, src.z, src.range)
        }
        for (prev in lastBaked.values) {
            markSectionsAround(pendingSections, prev.x, prev.y, prev.z, prev.range)
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
            e = e * 31 + src.x.toBits()
            e = e * 31 + src.y.toBits().rotateLeft(21)
            e = e * 31 + src.z.toBits().rotateLeft(42)
            e = e * 31 + src.range.toBits()
            e = e * 31 + (src.shape?.hashCode() ?: 0)
            e = e * 31 + src.lightQ
            e = e * 31 + src.rgb
            h += e * -7046029254386353131L
        }
        return h
    }

    /** @return 动态光照是否启用 */
    @JvmStatic
    fun isEnabled(): Boolean = ParticleDrawingConfig.CLIENT.enableDynamicLights.get()

    @JvmStatic
    fun hasActiveLights(): Boolean = lightBounds != null && isEnabled()

    @JvmStatic
    fun hasTerrainLightsAt(x: Int, y: Int, z: Int): Boolean =
        lightBounds?.contains(x.toDouble(), y.toDouble(), z.toDouble(), margin = 2.0) == true && isEnabled()

    @JvmStatic
    fun getDynamicLightLevel(x: Double, y: Double, z: Double): Double {
        var brightest = 0.0
        visitContributions(x, y, z) { _, level ->
            if (level > brightest) brightest = level
        }
        return brightest
    }

    private inline fun visitContributions(
        x: Double, y: Double, z: Double,
        sampleRadius: Double = 0.0,
        visit: (LightSource, Double) -> Unit,
    ) {
        if (lightBounds?.contains(x, y, z) != true) return
        LOCK.readLock().lock()
        try {
            if (sources.size <= 16) {
                for (src in sources) visit(src, contribution(src, x, y, z, sampleRadius))
                return
            }
            val cx = cellCoord(x)
            val cy = cellCoord(y)
            val cz = cellCoord(z)
            val range = cellRange
            for (dx in -range..range) {
                for (dy in -range..range) {
                    for (dz in -range..range) {
                        val cell = cellIndex[cellKey(cx + dx, cy + dy, cz + dz)] ?: continue
                        for (src in cell) visit(src, contribution(src, x, y, z, sampleRadius))
                    }
                }
            }
        } finally {
            LOCK.readLock().unlock()
        }
    }

    private fun contribution(src: LightSource, x: Double, y: Double, z: Double, sampleRadius: Double): Double =
        LightMath.contributionOf(
            x, y, z, src.x, src.y, src.z, src.luminance, src.range,
            src.dirX, src.dirY, src.dirZ, src.innerCos, src.outerCos, src.falloff, src.edge, sampleRadius,
        )

    /** 只抬高方块光，保留原版天空光与已照亮的方块。 */
    @JvmStatic
    fun getLightmapWithDynamicLight(lightmap: Int, x: Double, y: Double, z: Double): Int {
        if (!isEnabled()) return lightmap
        val dynamic = getDynamicLightLevel(x, y, z)
        return PlatformCompat.lightWithMinimumBlock(lightmap, dynamic)
    }

    /** 返回地形顶点的 ARGB（高 32 位）和光图坐标（低 32 位）。 */
    @JvmStatic
    fun shadeTerrainVertex(color: Int, lightmap: Int, x: Double, y: Double, z: Double): Long {
        var brightest = 0.0
        var weight = 0.0
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        visitContributions(x, y, z, TERRAIN_SAMPLE_RADIUS) { source, level ->
            if (level > 0.0) {
                if (level > brightest) brightest = level
                weight += level
                red += ((source.rgb shr 16) and 255) * level
                green += ((source.rgb shr 8) and 255) * level
                blue += (source.rgb and 255) * level
            }
        }
        val merged = PlatformCompat.lightWithMinimumBlock(lightmap, brightest)
        if (merged == lightmap) return packVertex(color, lightmap)
        terrainOverrides.increment()
        val tinted = tintTerrainColor(color, PlatformCompat.lightBlock(lightmap), brightest, weight, red, green, blue)
        return packVertex(tinted, merged)
    }

    // 颜色随新增光占比变化，光斑边缘不会先褪成亮白再变暗。
    internal fun tintTerrainColor(
        color: Int, block: Int, brightest: Double, weight: Double, red: Double, green: Double, blue: Double,
    ): Int {
        val tint = (brightest - block).coerceAtLeast(0.0) / (brightest + 2.0)
        fun channel(input: Int, total: Double): Int =
            (input * (1.0 - tint * (1.0 - total / (weight * 255.0)))).roundToInt().coerceIn(0, 255)
        val r = channel((color shr 16) and 255, red)
        val g = channel((color shr 8) and 255, green)
        val b = channel(color and 255, blue)
        return (color and 0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun packVertex(color: Int, lightmap: Int): Long =
        (color.toLong() shl 32) or (lightmap.toLong() and 0xffffffffL)

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
        radius: Double,
    ) {
        // 多留一格，覆盖环境遮蔽取相邻方块亮度的采样。
        val margin = radius + 1.0
        for (sx in cellCoord(x - margin)..cellCoord(x + margin)) {
            for (sy in cellCoord(y - margin)..cellCoord(y + margin)) {
                for (sz in cellCoord(z - margin)..cellCoord(z + margin)) {
                    out.add(SectionPos.asLong(sx, sy, sz))
                }
            }
        }
    }

    /** 切关卡时丢掉上一关的 pending 键与基准；同一关则保留跨帧积压。 */
    private fun ensurePendingLevel(level: ClientLevel) {
        val previous = pendingLevel
        if (previous != null && previous !== level) {
            pendingSections.clear()
            lastTerrainSubmitNs = null
            lastBaked.clear()
        }
        pendingLevel = level
    }

    /**
     * 把排队的脏 section 按固定间隔分批交给渲染器。
     *
     * 没提交的条目留在 [pendingSections] 里，下一帧继续；预算耗尽时也会保留请求。
     * 换关卡时旧 section 键作废，避免在新世界重建一批无关区块。
     */
    private fun applyPendingSections() {
        if (pendingSections.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) {
            pendingSections.clear()
            pendingLevel = null
            lastTerrainSubmitNs = null
            return
        }
        val previous = pendingLevel
        pendingLevel = level
        if (previous != null && previous !== level) {
            pendingSections.clear()
            lastTerrainSubmitNs = null
            return
        }

        val now = System.nanoTime()
        val last = lastTerrainSubmitNs
        if (last != null && now - last < TERRAIN_REFRESH_NANOS) return
        lastTerrainSubmitNs = now
        var budget = MAX_REBUILDS_PER_BATCH
        val it = pendingSections.iterator()
        while (it.hasNext() && budget > 0) {
            val packed = it.next()
            PlatformCompat.markSectionDirty(SectionPos.x(packed), SectionPos.y(packed), SectionPos.z(packed))
            it.remove()
            sectionsMarked++
            budget--
        }
    }
}
