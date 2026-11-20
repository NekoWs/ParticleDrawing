package work.nekow.particledrawing.light

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.WorldBounds
import kotlin.math.ceil
import kotlin.math.floor

/** 留出移动余量，只有越界或方块形状可能变化时才重新采样。 */
internal class LightOcclusionCache {
    private var region: WorldBounds? = null
    private var dirty = true
    private var shapes = emptyList<WorldBounds>()
    private var index = LightOcclusion.from(shapes)

    fun indexAt(origin: Vec3, range: Double, read: (WorldBounds) -> List<WorldBounds>): LightOcclusion {
        val center = Vec3(floor(origin.x), floor(origin.y), floor(origin.z))
        val radius = ceil(range) + BLOCK_MARGIN
        val required = bounds(center, radius)
        val cachedRegion = region
        val sampleRegion = if (cachedRegion != null &&
            required.low.x >= cachedRegion.low.x && required.high.x <= cachedRegion.high.x &&
            required.low.y >= cachedRegion.low.y && required.high.y <= cachedRegion.high.y &&
            required.low.z >= cachedRegion.low.z && required.high.z <= cachedRegion.high.z
        ) cachedRegion else {
            dirty = true
            bounds(center, radius + MOVE_MARGIN).also { region = it }
        }

        if (dirty) {
            val updated = read(sampleRegion)
            if (shapes != updated) {
                shapes = updated
                index = LightOcclusion.from(updated)
            }
            dirty = false
        }
        return index
    }

    fun blockChanged(x: Int, y: Int, z: Int) {
        val box = region ?: return
        // 栅栏等方块的碰撞形状也取决于相邻方块。
        if (overlapsXZ(box, x, x, z, z) && y >= box.low.y - BLOCK_MARGIN && y <= box.high.y + BLOCK_MARGIN) {
            dirty = true
        }
    }

    fun chunkChanged(minX: Int, maxX: Int, minZ: Int, maxZ: Int) {
        val box = region ?: return
        if (overlapsXZ(box, minX, maxX, minZ, maxZ)) dirty = true
    }

    private fun overlapsXZ(box: WorldBounds, minX: Int, maxX: Int, minZ: Int, maxZ: Int): Boolean =
        maxX >= box.low.x - BLOCK_MARGIN && minX <= box.high.x + BLOCK_MARGIN &&
            maxZ >= box.low.z - BLOCK_MARGIN && minZ <= box.high.z + BLOCK_MARGIN

    private fun bounds(center: Vec3, radius: Double): WorldBounds =
        WorldBounds(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))

    private companion object {
        const val BLOCK_MARGIN = 1.0
        const val MOVE_MARGIN = 3.0
    }
}
