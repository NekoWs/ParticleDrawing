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

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleDestroyPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_destroy")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleDestroyPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleDestroyPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleDestroyPayload {
                    val count = buf.readVarInt()
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
