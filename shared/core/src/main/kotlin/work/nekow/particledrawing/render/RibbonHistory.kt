package work.nekow.particledrawing.render

import work.nekow.particledrawing.math.Vec3

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

    data class Edge(val left: Vec3, val right: Vec3, val progress: Float)

    /**
     * 相邻段共用一条边。切向量取两侧采样点，相机对准切向时用上一条边的方向。
     * 进度从尾端的 0 到头部的 1，同时供宽度、UV 和透明度使用。
     */
    fun forEachSegment(head: Vec3, camera: Vec3, width: Float, partialTick: Float, emit: (Edge, Edge) -> Unit) {
        if (size < 2 || width <= 0f) return
        var side = Vec3.ZERO
        var edge: Edge? = null
        var previous = head
        for (i in 0 until size) {
            val point = if (i == 0) head else sample(i)
            val older = if (i + 1 < size) sample(i + 1) else point
            val tangent = previous.subtract(older).normalize()
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
            val age = if (i == 0) 0f else i - 1f + partialTick
            val progress = (1f - age / ticks).coerceIn(0f, 1f)
            val offset = side.scale(width.toDouble() * progress)
            val next = Edge(point.add(offset), point.subtract(offset), progress)
            if (edge != null && point != previous) emit(edge, next)
            edge = next
            previous = point
        }
    }

    private companion object {
        val UP = Vec3(0.0, 1.0, 0.0)
        val RIGHT = Vec3(1.0, 0.0, 0.0)
    }
}
