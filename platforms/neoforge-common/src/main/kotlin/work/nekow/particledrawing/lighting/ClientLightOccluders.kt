package work.nekow.particledrawing.lighting

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import work.nekow.particledrawing.core.client.ClientParticleWorld
import work.nekow.particledrawing.light.LightOcclusion
import work.nekow.particledrawing.light.LightOcclusionCache
import work.nekow.particledrawing.math.Vec3
import java.util.UUID

/** 只在客户端线程读取方块形状，地形线程消费不可变索引。 */
internal object ClientLightOccluders {
    class Snapshot(val index: LightOcclusion, val revision: Long, val origin: Vec3)

    private class Cached {
        val geometry = LightOcclusionCache()
        var index: LightOcclusion? = null
        var revision = 0L
    }

    private val cache = hashMapOf<UUID, Cached>()
    private var cachedLevel: ClientLevel? = null
    private var revision = 0L

    fun assign(level: ClientLevel, sources: List<DynamicLightManager.LightSource>, observer: Vec3, limit: Int) {
        if (limit == 0 || sources.isEmpty()) {
            clear()
            return
        }
        if (cachedLevel !== level) {
            cache.clear()
            cachedLevel = level
        }
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
            val cached = cache.getOrPut(source.id, ::Cached)
            val index = cached.geometry.indexAt(origin, source.range, ClientParticleWorld::blockShapes)
            if (cached.index !== index) {
                cached.index = index
                cached.revision = ++revision
            }
            source.occlusion = Snapshot(index, cached.revision, origin)
        }
        cache.keys.retainAll(incoming)
    }

    @JvmStatic
    fun blockChanged(level: ClientLevel, pos: BlockPos) {
        if (cachedLevel !== level) return
        for (entry in cache.values) entry.geometry.blockChanged(pos.x, pos.y, pos.z)
    }

    @JvmStatic
    fun chunkChanged(level: ClientLevel, pos: ChunkPos) {
        if (cachedLevel !== level) return
        for (entry in cache.values) entry.geometry.chunkChanged(pos.minBlockX, pos.maxBlockX, pos.minBlockZ, pos.maxBlockZ)
    }

    fun clear() {
        cache.clear()
        cachedLevel = null
    }

    const val MAX_RANGE = 16.0
}
