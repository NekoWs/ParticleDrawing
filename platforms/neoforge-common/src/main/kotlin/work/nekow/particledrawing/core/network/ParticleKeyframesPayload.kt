package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

internal data class ParticleKeyframesPayload(
    val asset: Identifier?, val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0, val yaw: Float = 0f,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ParticleKeyframesPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ParticleKeyframesPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_keyframes"),
        )
        val CODEC = object : StreamCodec<FriendlyByteBuf, ParticleKeyframesPayload> {
            override fun decode(buf: FriendlyByteBuf): ParticleKeyframesPayload {
                if (!buf.readBoolean()) return ParticleKeyframesPayload(null)
                return ParticleKeyframesPayload(Identifier.parse(buf.readUtf(256)), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat())
            }

            override fun encode(buf: FriendlyByteBuf, value: ParticleKeyframesPayload) {
                val asset = value.asset
                buf.writeBoolean(asset != null)
                if (asset == null) return
                buf.writeUtf(asset.toString(), 256)
                buf.writeDouble(value.x)
                buf.writeDouble(value.y)
                buf.writeDouble(value.z)
                buf.writeFloat(value.yaw)
            }
        }
    }
}
