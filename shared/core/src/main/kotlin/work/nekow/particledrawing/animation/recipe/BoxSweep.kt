package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.math.Vec3

/** 沿线段把粒子视为有半宽的立方体，查询与静止包围盒的首次接触。 */
internal object BoxSweep {
    private val NORMALS = arrayOf(
        Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0),
    )

    fun first(from: Vec3, to: Vec3, lower: Vec3, upper: Vec3, halfSize: Double): SurfaceHit? {
        if (from == to) return null
        val motion = to.subtract(from)
        val near = lower.add(-halfSize, -halfSize, -halfSize)
        val far = upper.add(halfSize, halfSize, halfSize)
        if (from.x > near.x && from.x < far.x && from.y > near.y && from.y < far.y && from.z > near.z && from.z < far.z) {
            return SurfaceHit(from, Vec3.ZERO, inside = true)
        }
        var enter = Double.NEGATIVE_INFINITY
        var exit = Double.POSITIVE_INFINITY
        var normal = Vec3.ZERO
        for (axis in NORMALS) {
            val point = from.dot(axis)
            val delta = motion.dot(axis)
            val min = near.dot(axis)
            val max = far.dot(axis)
            if (delta == 0.0) {
                if (point < min || point > max) return null
                continue
            }
            val advance = (min - point) / delta
            val retreat = (max - point) / delta
            val initial = minOf(advance, retreat)
            if (initial > enter) {
                enter = initial
                normal = if (delta > 0.0) axis.scale(-1.0) else axis
            }
            exit = minOf(exit, maxOf(advance, retreat))
            if (enter > exit) return null
        }
        if (enter !in 0.0..1.0) return null
        val center = from.add(motion.scale(enter))
        val contact = Vec3(
            center.x.coerceIn(lower.x, upper.x),
            center.y.coerceIn(lower.y, upper.y),
            center.z.coerceIn(lower.z, upper.z),
        )
        return SurfaceHit(contact, normal, center = center)
    }

    fun nearer(from: Vec3, first: SurfaceHit?, next: SurfaceHit?): SurfaceHit? = when {
        first == null -> next
        next == null -> first
        first.inside != next.inside -> if (first.inside) next else first
        from.distanceToSqr(first.center) <= from.distanceToSqr(next.center) -> first
        else -> next
    }
}
