package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 粒子实体锚点数据包：只在挂载时下发一次。之后客户端按实体 id 本地解析位置
 * （[local] 为 true 时连朝向一起），服务端不再为它广播位置——位置在客户端每 tick 本地求值，
 * 实体怎么动粒子就怎么动，也没有逐 tick 的带宽开销。
 *
 * @param particleId 粒子 ID
 * @param entityId 实体网络 id（客户端按 `level.getEntity` 解析）
 * @param ox/oy/oz 相对实体位置的偏移
 * @param local true = 偏移按实体朝向旋转（实体局部空间）；false = 世界空间偏移
 */
@Suppress("unused")
data class ParticleAttachPayload(
    val particleId: UUID,
    val entityId: Int,
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
                    val ox = buf.readDouble()
                    val oy = buf.readDouble()
                    val oz = buf.readDouble()
                    val local = buf.readBoolean()
                    return ParticleAttachPayload(id, entityId, ox, oy, oz, local)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleAttachPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeVarInt(p.entityId)
                    buf.writeDouble(p.ox)
                    buf.writeDouble(p.oy)
                    buf.writeDouble(p.oz)
                    buf.writeBoolean(p.local)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
