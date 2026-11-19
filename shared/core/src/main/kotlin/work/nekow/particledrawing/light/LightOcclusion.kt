package work.nekow.particledrawing.light

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.WorldBounds
import kotlin.math.sqrt

/** 对不可变形状快照查询光线遮挡，供并行的地形网格生成读取。 */
internal class LightOcclusion private constructor(private val root: Node?) {
    fun blocks(from: Vec3, x: Double, y: Double, z: Double): Boolean {
        val tree = root ?: return false
        val dx = x - from.x
        val dy = y - from.y
        val dz = z - from.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length <= 2.0 * END_MARGIN) return false
        val minimum = END_MARGIN / length
        return tree.blocks(from, Ray(dx, dy, dz, minimum))
    }

    private class Ray(x: Double, y: Double, z: Double, private val minimum: Double) {
        private val invX = 1.0 / x
        private val invY = 1.0 / y
        private val invZ = 1.0 / z

        fun hits(from: Vec3, box: WorldBounds): Boolean {
            if (invX.isInfinite() && (from.x <= box.low.x || from.x >= box.high.x)) return false
            if (invY.isInfinite() && (from.y <= box.low.y || from.y >= box.high.y)) return false
            if (invZ.isInfinite() && (from.z <= box.low.z || from.z >= box.high.z)) return false
            val x0 = (box.low.x - from.x) * invX; val x1 = (box.high.x - from.x) * invX
            val y0 = (box.low.y - from.y) * invY; val y1 = (box.high.y - from.y) * invY
            val z0 = (box.low.z - from.z) * invZ; val z1 = (box.high.z - from.z) * invZ
            val entry = maxOf(minimum, maxOf(minOf(x0, x1), minOf(y0, y1), minOf(z0, z1)))
            val exit = minOf(1.0 - minimum, minOf(maxOf(x0, x1), maxOf(y0, y1), maxOf(z0, z1)))
            return entry < exit
        }
    }

    private sealed class Node(val bounds: WorldBounds) {
        fun blocks(from: Vec3, ray: Ray): Boolean = ray.hits(from, bounds) && blocksInside(from, ray)
        protected abstract fun blocksInside(from: Vec3, ray: Ray): Boolean
    }

    private class Leaf(bounds: WorldBounds, private val boxes: Array<WorldBounds>) : Node(bounds) {
        override fun blocksInside(from: Vec3, ray: Ray): Boolean = boxes.any { ray.hits(from, it) }
    }

    private class Branch(bounds: WorldBounds, private val left: Node, private val right: Node) : Node(bounds) {
        override fun blocksInside(from: Vec3, ray: Ray): Boolean = left.blocks(from, ray) || right.blocks(from, ray)
    }

    companion object {
        private const val END_MARGIN = 0.002
        private const val LEAF_SIZE = 8
        private val byX = compareBy<WorldBounds> { it.low.x + it.high.x }
        private val byY = compareBy<WorldBounds> { it.low.y + it.high.y }
        private val byZ = compareBy<WorldBounds> { it.low.z + it.high.z }

        fun from(boxes: List<WorldBounds>): LightOcclusion =
            LightOcclusion(if (boxes.isEmpty()) null else build(boxes.toTypedArray(), 0, boxes.size))

        private fun build(boxes: Array<WorldBounds>, start: Int, end: Int): Node {
            val first = boxes[start]
            var minX = first.low.x; var minY = first.low.y; var minZ = first.low.z
            var maxX = first.high.x; var maxY = first.high.y; var maxZ = first.high.z
            for (index in start + 1 until end) {
                val box = boxes[index]
                minX = minOf(minX, box.low.x); minY = minOf(minY, box.low.y); minZ = minOf(minZ, box.low.z)
                maxX = maxOf(maxX, box.high.x); maxY = maxOf(maxY, box.high.y); maxZ = maxOf(maxZ, box.high.z)
            }
            val bounds = WorldBounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
            if (end - start <= LEAF_SIZE) return Leaf(bounds, boxes.copyOfRange(start, end))
            val x = maxX - minX; val y = maxY - minY; val z = maxZ - minZ
            val comparator = when {
                x >= y && x >= z -> byX
                y >= z -> byY
                else -> byZ
            }
            boxes.sortWith(comparator, start, end)
            val mid = (start + end) ushr 1
            return Branch(bounds, build(boxes, start, mid), build(boxes, mid, end))
        }
    }
}
