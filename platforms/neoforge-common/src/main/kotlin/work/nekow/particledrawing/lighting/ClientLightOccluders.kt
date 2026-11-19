package work.nekow.particledrawing.lighting

import net.minecraft.client.multiplayer.ClientLevel
import work.nekow.particledrawing.core.client.ClientParticleWorld
import work.nekow.particledrawing.light.LightOcclusion
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.render.WorldBounds
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/** 只在客户端线程读取方块形状，地形线程消费不可变索引。 */
internal object ClientLightOccluders {
    class Snapshot(val index: LightOcclusion, val revision: Long, val origin: Vec3)

    private data class Cached(
        val center: Vec3, val radius: Int, val checkedAt: Long,
        val shapes: List<WorldBounds>, val index: LightOcclusion, val revision: Long,
    )

    private val cache = hashMapOf<UUID, Cached>()
    private var revision = 0L

    fun assign(level: ClientLevel, sources: List<DynamicLightManager.LightSource>, observer: Vec3, limit: Int) {
        if (limit == 0 || sources.isEmpty()) {
            cache.clear()
            return
        }
        val now = level.gameTime
        val selected = sources.asSequence().filter { it.range <= MAX_RANGE }
            .sortedWith(compareBy<DynamicLightManager.LightSource> {
                val dx = observer.x - it.x; val dy = observer.y - it.y; val dz = observer.z - it.z
                dx * dx + dy * dy + dz * dz
            }.thenBy { it.id })
            .take(limit).toList()
        val incoming = hashSetOf<UUID>()
        for (source in selected) {
            incoming += source.id
            val origin = Vec3(source.x, source.y, source.z)
            val center = Vec3(floor(source.x), floor(source.y), floor(source.z))
            val radius = ceil(source.range).toInt() + 1
            val current = cache[source.id]
            val cached = if (current != null && current.center == center && current.radius == radius &&
                now - current.checkedAt in 0L until REFRESH_TICKS
            ) current else {
                val edge = radius.toDouble()
                val region = WorldBounds(center.add(-edge, -edge, -edge), center.add(edge, edge, edge))
                val shapes = ClientParticleWorld.blockShapes(region)
                val next = if (current != null && current.shapes == shapes) {
                    current.copy(center = center, radius = radius, checkedAt = now)
                } else {
                    Cached(center, radius, now, shapes, LightOcclusion.from(shapes), ++revision)
                }
                cache[source.id] = next
                next
            }
            source.occlusion = Snapshot(cached.index, cached.revision, origin)
        }
        cache.keys.retainAll(incoming)
    }

    fun clear() = cache.clear()

    const val MAX_RANGE = 16.0
    private const val REFRESH_TICKS = 10L
}
