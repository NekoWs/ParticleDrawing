package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 粒子销毁数据包，支持单个、批量和组的销毁。
 *
 * @param particleIds 要销毁的粒子 ID 数组
 * @param groupId 组 ID（组销毁时使用），可为 null
 */
data class ParticleDestroyPayload(
    val particleIds: Array<UUID>,
    val groupId: UUID?
) : CustomPacketPayload {

    init {
        // 解码端按同一上限拒绝，避免畸形条数触发大块内存分配
        require(particleIds.size <= MAX_BATCH) { "粒子销毁批量超限: ${particleIds.size} > $MAX_BATCH" }
    }

    companion object {
        /** 单包最大条数，超限显式拒绝。 */
        const val MAX_BATCH = 512

        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleDestroyPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_destroy")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleDestroyPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleDestroyPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleDestroyPayload {
                    val count = buf.readVarInt()
                    require(count in 0..MAX_BATCH) { "粒子销毁批量超限: $count" }
                    val ids = Array(count) { StreamCodecs.UUID_CODEC.decode(buf) }
                    val groupId = StreamCodecs.readNullableUUID(buf)
                    return ParticleDestroyPayload(ids, groupId)
                }

                override fun encode(buf: FriendlyByteBuf, payload: ParticleDestroyPayload) {
                    buf.writeVarInt(payload.particleIds.size)
                    for (id in payload.particleIds) StreamCodecs.UUID_CODEC.encode(buf, id)
                    StreamCodecs.writeNullableUUID(buf, payload.groupId)
                }
            }

        fun single(particleId: UUID): ParticleDestroyPayload {
            return ParticleDestroyPayload(arrayOf(particleId), null)
        }

        fun group(groupId: UUID, memberIds: Collection<UUID>): ParticleDestroyPayload {
            return ParticleDestroyPayload(memberIds.toTypedArray(), groupId)
        }

        @Suppress("unused")
        fun batch(ids: Collection<UUID>): ParticleDestroyPayload {
            return ParticleDestroyPayload(ids.toTypedArray(), null)
        }

        /**
         * 按 [MAX_BATCH] 拆成若干不超过上限的销毁包，用于组销毁与整维度清空。
         */
        fun chunked(ids: Collection<UUID>, groupId: UUID? = null): List<ParticleDestroyPayload> {
            val list = ids.toList()
            return BatchChunking.chunks(list, MAX_BATCH).map { ParticleDestroyPayload(it.toTypedArray(), groupId) }
        }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParticleDestroyPayload) return false
        if (!particleIds.contentEquals(other.particleIds)) return false
        return groupId == other.groupId
    }

    override fun hashCode(): Int {
        var result = particleIds.contentHashCode()
        result = 31 * result + (groupId?.hashCode() ?: 0)
        return result
    }
}
