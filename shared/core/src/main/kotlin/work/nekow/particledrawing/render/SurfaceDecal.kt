package work.nekow.particledrawing.render

import work.nekow.particledrawing.math.Vec3

data class WorldBounds(val low: Vec3, val high: Vec3)

/** 正交投影到碰撞形状的外侧表面。相同投影位置只保留最前的面。 */
internal object SurfaceDecal {
    data class Vertex(val position: Vec3, val u: Float, val v: Float)
    data class Patch(val vertices: List<Vertex>)

    private data class Rectangle(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        fun subtract(other: Rectangle): List<Rectangle> {
            val a = maxOf(x0, other.x0)
            val b = minOf(x1, other.x1)
            val c = maxOf(y0, other.y0)
            val d = minOf(y1, other.y1)
            if (a >= b || c >= d) return listOf(this)
            return listOfNotNull(
                if (x0 < a) Rectangle(x0, y0, a, y1) else null,
                if (b < x1) Rectangle(b, y0, x1, y1) else null,
                if (y0 < c) Rectangle(a, y0, b, c) else null,
                if (d < y1) Rectangle(a, d, b, y1) else null,
            )
        }
    }

    private data class Face(val rectangle: Rectangle, val offset: Double)

    fun project(origin: Vec3, normal: Vec3, radius: Double, shapes: List<WorldBounds>): List<Patch> {
        val axisX = if (normal.x == 0.0) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val axisY = normal.cross(axisX)
        if (axisY.lengthSqr() != 1.0 || normal.lengthSqr() != 1.0) return emptyList()

        val faces = shapes.mapNotNull { box ->
            val low = box.low.subtract(origin)
            val high = box.high.subtract(origin)
            val plane = maxOf(low.dot(normal), high.dot(normal))
            if (plane !in -DEPTH..DEPTH) return@mapNotNull null
            val x0 = maxOf(-radius, minOf(low.dot(axisX), high.dot(axisX)))
            val x1 = minOf(radius, maxOf(low.dot(axisX), high.dot(axisX)))
            val y0 = maxOf(-radius, minOf(low.dot(axisY), high.dot(axisY)))
            val y1 = minOf(radius, maxOf(low.dot(axisY), high.dot(axisY)))
            if (x0 >= x1 || y0 >= y1) null else Face(Rectangle(x0, y0, x1, y1), plane)
        }.sortedByDescending(Face::offset)

        val covered = arrayListOf<Rectangle>()
        val patches = arrayListOf<Patch>()
        for ((rectangle, depth) in faces) {
            var remaining = listOf(rectangle)
            for (occupied in covered) {
                remaining = remaining.flatMap { it.subtract(occupied) }
                if (remaining.isEmpty()) break
            }
            for (part in remaining) {
                covered += part
                fun point(x: Double, y: Double): Vertex {
                    val position = axisX.scale(x).add(axisY.scale(y)).add(normal.scale(depth + SURFACE_OFFSET))
                    return Vertex(position, ((x + radius) / (2.0 * radius)).toFloat(), ((y + radius) / (2.0 * radius)).toFloat())
                }
                patches += Patch(listOf(point(part.x0, part.y0), point(part.x1, part.y0), point(part.x1, part.y1), point(part.x0, part.y1)))
                if (patches.size == MAX_PATCHES) return patches
            }
        }
        return patches
    }

    const val DEPTH = 0.6
    private const val SURFACE_OFFSET = 0.001
    private const val MAX_PATCHES = 64
}
