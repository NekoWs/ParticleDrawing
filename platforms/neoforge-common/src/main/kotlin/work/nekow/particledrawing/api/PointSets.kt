package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.pointset.PointSet
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/**
 * 维度内命名的点集库。
 *
 * 点集只存相对坐标，不给任何消费者绑死；对象跟组、模板一样是内存数据，不写存档，
 * 关卡卸载与停服时丢弃。命令层与模组共用这一份注册表。
 */
object PointSets {
    /** 一条点集记录；[revision] 每次覆盖递增，消费方可用它判断缓存是否过期。 */
    data class Entry(val points: PointSet, val source: String, val revision: Int)

    private const val MAX_ENTRIES = 256

    private val dimensions = hashMapOf<UUID, LinkedHashMap<String, Entry>>()
    private var revisions = 0

    fun names(dimensionId: UUID): List<String> = dimensions[dimensionId]?.keys?.toList() ?: emptyList()

    fun names(level: ServerLevel): List<String> = names(ParticleUtils.dimensionUUID(level))

    fun find(dimensionId: UUID, name: String): Entry? = dimensions[dimensionId]?.get(name)

    fun find(level: ServerLevel, name: String): Entry? = find(ParticleUtils.dimensionUUID(level), name)

    fun get(level: ServerLevel, name: String): Entry = get(ParticleUtils.dimensionUUID(level), name)

    fun get(dimensionId: UUID, name: String): Entry =
        find(dimensionId, name) ?: throw LocalizedArgumentException(translation("error.pointset.not_found", name))

    /** 定义前先检查容量与重名，避免先采样再失败。 */
    fun checkCapacity(level: ServerLevel, name: String) = checkCapacity(ParticleUtils.dimensionUUID(level), name)

    fun checkCapacity(dimensionId: UUID, name: String) {
        val entries = dimensions[dimensionId].orEmpty()
        requireLocalized(name in entries || entries.size < MAX_ENTRIES) { translation("error.pointset.limit", MAX_ENTRIES) }
    }

    /** 覆盖式定义；空点集与超限在 [PointSet] 构造时已被拦住。 */
    fun define(level: ServerLevel, name: String, points: PointSet, source: String): Entry =
        define(ParticleUtils.dimensionUUID(level), name, points, source)

    fun define(dimensionId: UUID, name: String, points: PointSet, source: String): Entry {
        requireLocalized(!points.isEmpty) { translation("error.pointset.empty") }
        checkCapacity(dimensionId, name)
        val entry = Entry(points, source, ++revisions)
        dimensions.getOrPut(dimensionId) { linkedMapOf() }[name] = entry
        return entry
    }

    fun remove(level: ServerLevel, names: List<String>) = remove(ParticleUtils.dimensionUUID(level), names)

    fun remove(dimensionId: UUID, names: List<String>) {
        val entries = dimensions[dimensionId] ?: return
        entries.keys.removeAll(names.toSet())
        if (entries.isEmpty()) dimensions.remove(dimensionId)
    }

    fun discard(dimensionId: UUID) { dimensions.remove(dimensionId) }

    fun clear() { dimensions.clear() }
}
