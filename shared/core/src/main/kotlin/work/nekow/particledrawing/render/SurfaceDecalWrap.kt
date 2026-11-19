package work.nekow.particledrawing.render

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.SurfaceDecal.Rectangle
import kotlin.math.abs

/** 沿真实外露面展开贴图，同一个棱边在相邻面上保持连续的 UV。 */
internal object SurfaceDecalWrap {
    private enum class Axis(val vector: Vec3) {
        X(Vec3(1.0, 0.0, 0.0)), Y(Vec3(0.0, 1.0, 0.0)), Z(Vec3(0.0, 0.0, 1.0));

        fun coordinate(point: Vec3) = point.dot(vector)
    }

    private data class Face(val axis: Axis, val sign: Int, val plane: Double, val rect: Rectangle) {
        val normal get() = axis.vector.scale(sign.toDouble())
        val tangents get() = tangentAxes[axis.ordinal]
        fun point(x: Double, y: Double): Vec3 =
            axis.vector.scale(plane).add(tangents.first.vector.scale(x)).add(tangents.second.vector.scale(y))
        fun contains(point: Vec3): Boolean =
            tangents.first.coordinate(point) in rect.x0 - EPS..rect.x1 + EPS &&
                tangents.second.coordinate(point) in rect.y0 - EPS..rect.y1 + EPS

        fun limits(along: Axis): Pair<Double, Double> =
            if (along == tangents.first) rect.x0 to rect.x1 else rect.y0 to rect.y1
    }

    private data class Mapping(val origin: Vec3, val right: Vec3, val up: Vec3) {
        fun uvBounds(face: Face): Rectangle {
            val low = face.point(face.rect.x0, face.rect.y0).subtract(origin)
            val high = face.point(face.rect.x1, face.rect.y1).subtract(origin)
            val u0 = low.dot(right); val u1 = high.dot(right)
            val v0 = low.dot(up); val v1 = high.dot(up)
            return Rectangle(minOf(u0, u1), minOf(v0, v1), maxOf(u0, u1), maxOf(v0, v1))
        }

        fun fold(at: Vec3, from: Face, to: Face): Mapping {
            val edge = from.normal.cross(to.normal)
            fun rotate(v: Vec3) = edge.cross(v).add(edge.scale(edge.dot(v)))
            val nextRight = rotate(right)
            val nextUp = rotate(up)
            val texel = at.subtract(origin)
            return Mapping(at.subtract(nextRight.scale(texel.dot(right))).subtract(nextUp.scale(texel.dot(up))), nextRight, nextUp)
        }
    }

    fun project(
        origin: Vec3, normal: Vec3, radius: Double, shapes: List<WorldBounds>, relativeTo: Vec3 = origin,
    ): List<SurfaceDecal.Patch> {
        val axis = Axis.entries.firstOrNull { abs(it.coordinate(normal)) == 1.0 } ?: return emptyList()
        val faces = exposedFaces(shapes, origin, radius)
        val plane = axis.coordinate(origin)
        val root = faces.asSequence()
            .filter { it.axis == axis && it.normal == normal && it.contains(origin) }
            .minByOrNull { abs(it.plane - plane) }
            ?.takeIf { abs(it.plane - plane) <= SurfaceDecal.SURFACE_OFFSET * 2 }
            ?: return emptyList()
        val right = root.tangents.first.vector
        val mapping = Mapping(origin.add(axis.vector.scale(root.plane - plane)), right, normal.cross(right))
        val footprint = Rectangle(-radius, -radius, radius, radius)
        val visited = hashSetOf(root)
        val queue = ArrayDeque<Pair<Face, Mapping>>()
        queue.addLast(root to mapping)
        val result = arrayListOf<SurfaceDecal.Patch>()
        while (queue.isNotEmpty() && result.size < SurfaceDecal.MAX_PATCHES) {
            val (face, placement) = queue.removeFirst()
            val clipped = placement.uvBounds(face).clip(footprint) ?: continue
            val center = placement.origin.subtract(relativeTo).add(face.normal.scale(SurfaceDecal.SURFACE_OFFSET))
            result += SurfaceDecal.patch(clipped, center, placement.right, placement.up, radius)
            for (neighbor in faces) {
                if (neighbor in visited) continue
                val next = if (neighbor.axis == face.axis) {
                    if (!coplanarNeighbors(face, neighbor)) continue
                    placement
                } else {
                    val edge = sharedEdge(face, neighbor) ?: continue
                    placement.fold(edge, face, neighbor)
                }
                if (!next.uvBounds(neighbor).intersects(footprint)) continue
                visited += neighbor
                queue.addLast(neighbor to next)
            }
        }
        return result
    }

    private fun exposedFaces(boxes: List<WorldBounds>, center: Vec3, radius: Double): Set<Face> {
        val radiusVector = Vec3(radius, radius, radius)
        val footprint = WorldBounds(center.subtract(radiusVector), center.add(radiusVector))
        val faces = linkedSetOf<Face>()
        for (axis in Axis.entries) {
            val (x, y) = tangentAxes[axis.ordinal]
            fun bounds(box: WorldBounds) = Rectangle(
                x.coordinate(box.low), y.coordinate(box.low), x.coordinate(box.high), y.coordinate(box.high),
            )
            val window = bounds(footprint)
            val blockers = boxes.mapNotNull { box ->
                val inWindow = bounds(box).clip(window) ?: return@mapNotNull null
                Blocker(axis.coordinate(box.low), axis.coordinate(box.high), inWindow)
            }
            val byPlane = hashMapOf<Pair<Double, Int>, MutableList<Rectangle>>()
            for (block in blockers) for (sign in -1..1 step 2) {
                val plane = if (sign > 0) block.high else block.low
                if (plane !in axis.coordinate(footprint.low)..axis.coordinate(footprint.high)) continue
                var visible = listOf(block.rect)
                for (neighbor in blockers) {
                    if (!neighbor.covers(plane, sign)) continue
                    visible = visible.flatMap { it.subtract(neighbor.rect) }
                    if (visible.isEmpty()) break
                }
                if (visible.isEmpty()) continue
                val covered = byPlane.getOrPut(plane to sign) { arrayListOf() }
                for (previous in covered) {
                    visible = visible.flatMap { it.subtract(previous) }
                    if (visible.isEmpty()) break
                }
                covered.addAll(visible)
                for (rect in visible) faces += Face(axis, sign, plane, rect)
            }
        }
        return faces
    }

    private data class Blocker(val low: Double, val high: Double, val rect: Rectangle) {
        fun covers(plane: Double, side: Int) =
            if (side > 0) low <= plane + EPS && high > plane + EPS else high >= plane - EPS && low < plane - EPS
    }

    private fun coplanarNeighbors(a: Face, b: Face): Boolean =
        a.sign == b.sign && near(a.plane, b.plane) &&
            ((near(a.rect.x1, b.rect.x0) || near(b.rect.x1, a.rect.x0)) && overlap(a.rect.y0, a.rect.y1, b.rect.y0, b.rect.y1) ||
                (near(a.rect.y1, b.rect.y0) || near(b.rect.y1, a.rect.y0)) && overlap(a.rect.x0, a.rect.x1, b.rect.x0, b.rect.x1))

    private fun sharedEdge(a: Face, b: Face): Vec3? {
        val (a0, a1) = a.limits(b.axis)
        val (b0, b1) = b.limits(a.axis)
        if (!(near(b.plane, a0) || near(b.plane, a1)) || !(near(a.plane, b0) || near(a.plane, b1))) return null
        val along = Axis.entries[3 - a.axis.ordinal - b.axis.ordinal]
        val (startA, endA) = a.limits(along)
        val (startB, endB) = b.limits(along)
        val start = maxOf(startA, startB)
        val end = minOf(endA, endB)
        if (end - start <= EPS) return null
        return a.axis.vector.scale(a.plane).add(b.axis.vector.scale(b.plane)).add(along.vector.scale((start + end) * 0.5))
    }

    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double) = minOf(a1, b1) - maxOf(a0, b0) > EPS
    private fun near(a: Double, b: Double) = abs(a - b) <= EPS
    private val tangentAxes = listOf(Axis.Y to Axis.Z, Axis.X to Axis.Z, Axis.X to Axis.Y)
    private const val EPS = 1e-8
}
