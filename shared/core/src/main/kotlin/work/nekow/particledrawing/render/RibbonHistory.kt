package work.nekow.particledrawing.render

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.api.ParticleTrailStyle

/** 固定长度的刻级世界坐标历史。首点由渲染器按分数刻插值。 */
internal class RibbonHistory(private val ticks: Int, start: Vec3) {
    private val samples = arrayOfNulls<Vec3>(ticks + 1)
    private var newest = 0
    var size = 1
        private set

    init {
        samples[0] = start
    }

    fun record(position: Vec3) {
        val previous = sample(0)
        newest = (newest + 1) % samples.size
        samples[newest] = if (previous == position) previous else position
        size = minOf(size + 1, samples.size)
    }

    fun reset(position: Vec3) {
        samples.fill(null)
        newest = 0
        samples[0] = position
        size = 1
    }

    fun sample(age: Int): Vec3 = checkNotNull(samples[(newest - age + samples.size) % samples.size])

    fun hasPath(): Boolean = (1 until size).any { sample(it) != sample(0) }

    fun oldestFirst(): List<Vec3> = List(size) { sample(size - 1 - it) }

    fun restore(points: List<Vec3>) {
        require(points.size in 1..samples.size)
        reset(points.first())
        for (i in 1 until points.size) record(points[i])
    }

    fun copyFrom(history: RibbonHistory) {
        require(history.samples.size == samples.size)
        history.samples.copyInto(samples)
        newest = history.newest
        size = history.size
    }

    data class Edge(val left: Vec3, val right: Vec3, val progress: Float)
    data class Ring(val vertices: List<Vec3>, val progress: Float)

    private inline fun walk(head: Vec3, partialTick: Float, byDistance: Boolean, visit: (Vec3, Vec3, Float, Boolean) -> Unit) {
        var length = 0.0
        if (byDistance) {
            var last = head
            for (i in 1 until size) {
                val point = sample(i)
                length += last.distanceTo(point)
                last = point
            }
        }
        var travelled = 0.0
        var previous = head
        for (i in 0 until size) {
            val point = if (i == 0) head else sample(i)
            val older = if (i + 1 < size) sample(i + 1) else point
            val tangent = previous.subtract(older).normalize()
            val age = if (i == 0) 0f else i - 1f + partialTick
            val progress = if (length > 0.0) {
                travelled += previous.distanceTo(point)
                (1.0 - travelled / length).toFloat().coerceIn(0f, 1f)
            } else (1f - age / ticks).coerceIn(0f, 1f)
            visit(point, tangent, progress, point != previous)
            previous = point
        }
    }

    /**
     * 相邻段共用一条边。切向量取两侧采样点，相机对准切向时用上一条边的方向。
     * 进度从尾端的 0 到头部的 1，同时供宽度、UV 和透明度使用。
     */
    fun forEachSegment(head: Vec3, camera: Vec3, width: Float, partialTick: Float,
                       style: ParticleTrailStyle = ParticleTrailStyle.DEFAULT, emit: (Edge, Edge) -> Unit) {
        if (size < 2 || width <= 0f) return
        var side = Vec3.ZERO
        var edge: Edge? = null
        walk(head, partialTick, style.byDistance) { point, tangent, progress, moved ->
            var nextSide = tangent.cross(camera.subtract(point)).normalize()
            if (nextSide == Vec3.ZERO) {
                nextSide = side
                if (nextSide == Vec3.ZERO) {
                    val up = if (kotlin.math.abs(tangent.y) < 0.9) UP else RIGHT
                    nextSide = tangent.cross(up).normalize()
                }
            }
            if (side.dot(nextSide) < 0.0) nextSide = nextSide.scale(-1.0)
            side = nextSide
            val offset = side.scale(width.toDouble() * style.widthAt(progress))
            val next = Edge(point.add(offset), point.subtract(offset), progress)
            edge?.let { if (moved) emit(it, next) }
            edge = next
        }
    }

    fun forEachTubeSegment(head: Vec3, width: Float, partialTick: Float, sides: Int,
                          style: ParticleTrailStyle = ParticleTrailStyle.DEFAULT, emit: (Ring, Ring) -> Unit) {
        if (size < 2 || width <= 0f) return
        val profile = requireNotNull(profiles.getOrNull(sides))
        var ring: Ring? = null
        var lastTangent = Vec3.ZERO
        var normal = Vec3.ZERO
        walk(head, partialTick, style.byDistance) { point, tangent, progress, moved ->
            val forward = if (tangent != Vec3.ZERO) tangent else lastTangent
            if (forward != Vec3.ZERO) {
                val projected = normal.subtract(forward.scale(normal.dot(forward))).normalize()
                normal = if (projected != Vec3.ZERO) projected
                    else forward.cross(if (kotlin.math.abs(forward.y) < 0.9) UP else RIGHT).normalize()
                val binormal = forward.cross(normal)
                val radius = width.toDouble() * style.widthAt(progress)
                val vertices = profile.map { (x, y) ->
                    point.add(normal.scale(x * radius)).add(binormal.scale(y * radius))
                }
                val next = Ring(vertices, progress)
                ring?.let { if (moved) emit(it, next) }
                ring = next
                lastTangent = forward
            }
        }
    }

    private companion object {
        val UP = Vec3(0.0, 1.0, 0.0)
        val RIGHT = Vec3(1.0, 0.0, 0.0)
        val profiles = List(13) { sides ->
            if (sides < 3) null else List(sides) { i ->
                val angle = 2.0 * Math.PI * i / sides
                kotlin.math.cos(angle) to kotlin.math.sin(angle)
            }
        }
    }
}
