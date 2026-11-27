package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import work.nekow.particledrawing.core.network.FieldPlacement
import work.nekow.particledrawing.core.network.ParticleFieldSnapshot
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/** 维度内共享的粒子力场。服务端主线程配置，客户端随锚点采样。 */
object ParticleForceFields {
    private val dimensions = hashMapOf<UUID, LinkedHashMap<ResourceId, FieldPlacement>>()

    @JvmStatic
    fun set(level: ServerLevel, id: ResourceId, spec: ParticleForceField, anchor: RecipeAnchor) {
        val fields = dimensions.getOrPut(ParticleUtils.dimensionUUID(level)) { linkedMapOf() }
        requireLocalized(id in fields || fields.size < ParticleForceField.MAX_FIELDS) {
            translation("error.field.limit", ParticleForceField.MAX_FIELDS)
        }
        fields[id] = FieldPlacement(spec, anchor)
        broadcast(level, fields)
    }

    @JvmStatic
    fun anchor(level: ServerLevel, id: ResourceId, anchor: RecipeAnchor) {
        val fields = dimensions[ParticleUtils.dimensionUUID(level)]
        val old = requireNotNullLocalized(fields?.get(id)) { translation("error.field.not_found", id) }
        set(level, id, old.spec, anchor)
    }

    @JvmStatic
    fun remove(level: ServerLevel, id: ResourceId): Boolean = removeAll(level, listOf(id)) != 0

    internal fun removeAll(level: ServerLevel, ids: Collection<ResourceId>): Int {
        val dimension = ParticleUtils.dimensionUUID(level)
        val fields = dimensions[dimension] ?: return 0
        val removed = ids.count { fields.remove(it) != null }
        if (removed == 0) return 0
        if (fields.isEmpty()) dimensions.remove(dimension)
        broadcast(level, fields)
        return removed
    }

    @JvmStatic
    fun ids(level: ServerLevel): List<ResourceId> =
        dimensions[ParticleUtils.dimensionUUID(level)]?.keys?.toList() ?: emptyList()

    internal fun sync(player: ServerPlayer) {
        val fields = dimensions[ParticleUtils.dimensionUUID(player.level())] ?: return
        PacketDistributor.sendToPlayer(player, ParticleFieldSnapshot(fields.values.toList()))
    }

    internal fun placements(level: ServerLevel): Collection<FieldPlacement> =
        dimensions[ParticleUtils.dimensionUUID(level)]?.values.orEmpty()

    internal fun discard(dimension: UUID) { dimensions.remove(dimension) }

    internal fun clear() = dimensions.clear()

    private fun broadcast(level: ServerLevel, fields: Map<ResourceId, FieldPlacement>) {
        val payload = ParticleFieldSnapshot(fields.values.toList())
        for (player in level.players()) PacketDistributor.sendToPlayer(player, payload)
    }
}
