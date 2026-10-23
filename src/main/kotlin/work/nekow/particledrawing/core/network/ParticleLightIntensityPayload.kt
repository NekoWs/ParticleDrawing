package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * 运行时修改发光粒子的对外光强乘数。
 *
 * [durationTicks] > 0 时客户端从当前实际亮度接续到 [multiplier]，不会先跳回满光；
 * 0 = 立即生效。只作用于动态光照，不改变片元透明度与全亮标志。
 *
 * @param multiplier 目标乘数（>= 0；1 = 原始亮度）
 */
data class ParticleLightIntensityPayload(
    val particleId: UUID,
    val multiplier: Float,
    val durationTicks: Int = 0,
    val easing: EasingType = EasingType.LINEAR,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleLightIntensityPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_light_intensity")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleLightIntensityPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleLightIntensityPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleLightIntensityPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val raw = buf.readFloat()
                    val multiplier = if (raw.isFinite() && raw >= 0f) raw else 1f
                    val duration = buf.readVarInt()
                    val easing = readEasingCompact(buf)
                    return ParticleLightIntensityPayload(id, multiplier, duration, easing)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleLightIntensityPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeFloat(p.multiplier)
                    buf.writeVarInt(p.durationTicks)
                    writeEasingCompact(buf, p.easing)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
