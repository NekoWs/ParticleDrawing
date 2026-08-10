package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 持续旋转控制包。服务端发送此包后，客户端开始/停止自动旋转组内所有粒子。
 * 客户端每帧从原始位置重新计算旋转角度，实现零漂移、丝滑旋转。
 *
 * @param groupId 组 ID
 * @param active 是否启用持续旋转
 * @param ax/ay/az 旋转轴（归一化）
 * @param radiansPerTick 每 tick 旋转弧度
 * @param px/py/pz 旋转轴心
 */
data class ContinuousRotationPayload(
    val groupId: UUID,
    val active: Boolean,
    val ax: Double, val ay: Double, val az: Double,
    val radiansPerTick: Double,
    val px: Double, val py: Double, val pz: Double
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ContinuousRotationPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "continuous_rotation")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ContinuousRotationPayload> =
            object : StreamCodec<FriendlyByteBuf, ContinuousRotationPayload> {
                override fun decode(buf: FriendlyByteBuf): ContinuousRotationPayload {
                    return ContinuousRotationPayload(
                        UUID(buf.readLong(), buf.readLong()),
                        buf.readBoolean(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble()
                    )
                }

                override fun encode(buf: FriendlyByteBuf, p: ContinuousRotationPayload) {
                    buf.writeLong(p.groupId.mostSignificantBits)
                    buf.writeLong(p.groupId.leastSignificantBits)
                    buf.writeBoolean(p.active)
                    buf.writeDouble(p.ax); buf.writeDouble(p.ay); buf.writeDouble(p.az)
                    buf.writeDouble(p.radiansPerTick)
                    buf.writeDouble(p.px); buf.writeDouble(p.py); buf.writeDouble(p.pz)
                }
            }
    }

    override fun type() = TYPE
}
