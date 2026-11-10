package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 粒子实体锚点数据包：只在挂载时下发一次，之后客户端每 tick 按实体本地解析位置
 * （[local] 为 true 时连朝向一起），服务端不再为它广播位置。
 *
 * @param particleId 粒子 ID
 * @param entityId 实体网络 id；按 uuid 挂载且当拍未解析到时为 -1
 * @param entityUuid 实体 UUID；非 null 时客户端优先按它解析，网络 id 会随实体重载或换维度变化
 * @param ox/oy/oz 相对实体位置的偏移
 * @param local true = 偏移按实体朝向旋转（实体局部空间）；false = 世界空间偏移
 */
@Suppress("unused")
data class ParticleAttachPayload(
    val particleId: UUID,
    val entityId: Int,
    val entityUuid: UUID?,
    val ox: Double, val oy: Double, val oz: Double,
    val local: Boolean,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleAttachPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_attach")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleAttachPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleAttachPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleAttachPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val entityId = buf.readVarInt()
                    val entityUuid = StreamCodecs.readNullableUUID(buf)
                    val ox = buf.readDouble()
                    val oy = buf.readDouble()
                    val oz = buf.readDouble()
                    val local = buf.readBoolean()
                    return ParticleAttachPayload(id, entityId, entityUuid, ox, oy, oz, local)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleAttachPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeVarInt(p.entityId)
                    StreamCodecs.writeNullableUUID(buf, p.entityUuid)
                    buf.writeDouble(p.ox)
                    buf.writeDouble(p.oy)
                    buf.writeDouble(p.oz)
                    buf.writeBoolean(p.local)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
