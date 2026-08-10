package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

data class ParticleUpdatePayload(
    val particleId: UUID,
    val x: Double, val y: Double, val z: Double,
    val r: Float, val g: Float, val b: Float, val a: Float,
    val scale: Float,
    val durationTicks: Int,
    val hasPosition: Boolean,
    val hasColor: Boolean,
    val hasScale: Boolean,
    val e0: Double, val e1: Double, val e2: Double, val e3: Double, val e4: Double
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleUpdatePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_update")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleUpdatePayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleUpdatePayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleUpdatePayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val x = buf.readDouble()
                    val y = buf.readDouble()
                    val z = buf.readDouble()
                    val r = buf.readFloat()
                    val g = buf.readFloat()
                    val b = buf.readFloat()
                    val a = buf.readFloat()
                    val scale = buf.readFloat()
                    val dur = ByteBufCodecs.VAR_INT.decode(buf)
                    val hp = buf.readBoolean()
                    val hc = buf.readBoolean()
                    val hs = buf.readBoolean()
                    val e0 = buf.readDouble()
                    val e1 = buf.readDouble()
                    val e2 = buf.readDouble()
                    val e3 = buf.readDouble()
                    val e4 = buf.readDouble()
                    return ParticleUpdatePayload(id, x, y, z, r, g, b, a, scale, dur, hp, hc, hs, e0, e1, e2, e3, e4)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleUpdatePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeDouble(p.x)
                    buf.writeDouble(p.y)
                    buf.writeDouble(p.z)
                    buf.writeFloat(p.r)
                    buf.writeFloat(p.g)
                    buf.writeFloat(p.b)
                    buf.writeFloat(p.a)
                    buf.writeFloat(p.scale)
                    ByteBufCodecs.VAR_INT.encode(buf, p.durationTicks)
                    buf.writeBoolean(p.hasPosition)
                    buf.writeBoolean(p.hasColor)
                    buf.writeBoolean(p.hasScale)
                    buf.writeDouble(p.e0)
                    buf.writeDouble(p.e1)
                    buf.writeDouble(p.e2)
                    buf.writeDouble(p.e3)
                    buf.writeDouble(p.e4)
                }
            }

        fun positionOnly(id: UUID, x: Double, y: Double, z: Double,
                         durationTicks: Int, easing: EasingType): ParticleUpdatePayload {
            val ser = easing.serialize()
            return ParticleUpdatePayload(id, x, y, z, 0f, 0f, 0f, 0f, 0f, durationTicks,
                true, false, false, ser[0], ser[1], ser[2], ser[3], ser[4])
        }

        fun colorOnly(id: UUID, r: Float, g: Float, b: Float, a: Float,
                      durationTicks: Int, easing: EasingType): ParticleUpdatePayload {
            val ser = easing.serialize()
            return ParticleUpdatePayload(id, 0.0, 0.0, 0.0, r, g, b, a, 0f, durationTicks,
                false, true, false, ser[0], ser[1], ser[2], ser[3], ser[4])
        }

        fun scaleOnly(id: UUID, scale: Float,
                      durationTicks: Int, easing: EasingType): ParticleUpdatePayload {
            val ser = easing.serialize()
            return ParticleUpdatePayload(id, 0.0, 0.0, 0.0, 0f, 0f, 0f, 0f, scale, durationTicks,
                false, false, true, ser[0], ser[1], ser[2], ser[3], ser[4])
        }

        fun full(id: UUID,
                 x: Double, y: Double, z: Double,
                 r: Float, g: Float, b: Float, a: Float,
                 scale: Float, durationTicks: Int, easing: EasingType): ParticleUpdatePayload {
            val ser = easing.serialize()
            return ParticleUpdatePayload(id, x, y, z, r, g, b, a, scale, durationTicks,
                true, true, true, ser[0], ser[1], ser[2], ser[3], ser[4])
        }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun easingType(): EasingType = EasingType.deserialize(doubleArrayOf(e0, e1, e2, e3, e4))
}
