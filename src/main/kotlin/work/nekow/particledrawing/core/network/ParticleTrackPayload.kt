package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * 粒子「直设位置」数据包：把粒子直接定位到目标位置，客户端用 partialTick
 * 在上一位置与本位置之间插值（无缓动滞后）。
 *
 * 供「每 tick 跟随一个非实体点」的粒子（如投射物本体）使用：位置精确且渲染丝滑，
 * 不会像缓动那样永远比真实位置慢一拍。
 *
 * @param particleId 粒子唯一 ID
 * @param x/y/z 目标世界坐标
 */
data class ParticleTrackPayload(
    val particleId: UUID,
    val x: Double, val y: Double, val z: Double
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleTrackPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_track")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleTrackPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleTrackPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleTrackPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val x = buf.readDouble()
                    val y = buf.readDouble()
                    val z = buf.readDouble()
                    return ParticleTrackPayload(id, x, y, z)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleTrackPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeDouble(p.x)
                    buf.writeDouble(p.y)
                    buf.writeDouble(p.z)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}