package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.AABB
import work.nekow.particledrawing.math.Vec3

/** 一对相邻样本的柔边光带。节点的两侧使用同一个切向，段与段共享边。 */
internal class RecipeStrand {
    var previous: BridgeParticle? = null
    var next: BridgeParticle? = null

    fun bounds(start: BridgeParticle, partialTick: Float): AABB? {
        val end = next ?: return null
        val a = start.strandPosition(partialTick)
        val b = end.strandPosition(partialTick)
        val width = maxOf(start.sampledHeight(partialTick), end.sampledHeight(partialTick), 0f).toDouble()
        return AABB(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z),
            maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z)).inflate(width)
    }

    fun append(
        start: BridgeParticle, vertices: RibbonVertices, camera: Vec3, partialTick: Float,
        u: Float, v0: Float, v1: Float, light: Int,
    ) {
        val end = next ?: return
        val a = start.strandPosition(partialTick)
        val b = end.strandPosition(partialTick)
        if (a.distanceToSqr(b) < 1.0E-8) return
        val aColor = start.strandTint(partialTick, a)
        val bColor = end.strandTint(partialTick, b)
        if ((aColor ushr 24 == 0 && bColor ushr 24 == 0) || !ParticleGeometryBudget.reserve(1)) return

        val before = previous?.strandPosition(partialTick) ?: a
        val after = end.recipeStrand?.next?.strandPosition(partialTick) ?: b
        val axisA = widthAxis(before, a, b, camera).scale(start.sampledHeight(partialTick).coerceAtLeast(0f).toDouble())
        val axisB = widthAxis(a, b, after, camera).scale(end.sampledHeight(partialTick).coerceAtLeast(0f).toDouble())
        vertices.addVertex(a.add(axisA), camera, u, v1, aColor, light)
        vertices.addVertex(a.subtract(axisA), camera, u, v0, aColor, light)
        vertices.addVertex(b.subtract(axisB), camera, u, v0, bColor, light)
        vertices.addVertex(b.add(axisB), camera, u, v1, bColor, light)
    }

    private fun widthAxis(before: Vec3, center: Vec3, after: Vec3, camera: Vec3): Vec3 {
        var tangent = after.subtract(before)
        if (tangent.lengthSqr() < 1.0E-8) tangent = center.subtract(before)
        val across = tangent.cross(camera.subtract(center)).normalize()
        if (across.lengthSqr() > 0.0) return across
        val basis = if (kotlin.math.abs(tangent.y) < kotlin.math.abs(tangent.x)) UP else RIGHT
        return tangent.cross(basis).normalize()
    }

    companion object {
        private val UP = Vec3(0.0, 1.0, 0.0)
        private val RIGHT = Vec3(1.0, 0.0, 0.0)
    }
}
