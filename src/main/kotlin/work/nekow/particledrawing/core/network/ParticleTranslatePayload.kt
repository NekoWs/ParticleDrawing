package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * 粒子平移数据包：绕 [pivot] 轴心在 [offset] 基础上叠加一个平移增量 [tx/ty/tz]（世界空间）。
 * 与旋转独立缓动，客户端将其与旋转叠加，得到与编辑器一致的最终位置。
 *
 * @param particleId 粒子 ID
 * @param px/py/pz 旋转轴心（绝对世界坐标）
 * @param ox/oy/oz 粒子相对轴心的偏移向量
 * @param tx/ty/tz 目标平移增量
 * @param durationTicks 过渡持续 tick 数
 * @param e0-e4 缓动曲线序列化参数
 */
data class ParticleTranslatePayload(
    val particleId: UUID,
    val px: Double, val py: Double, val pz: Double,
    val ox: Double, val oy: Double, val oz: Double,
    val tx: Double, val ty: Double, val tz: Double,
    val durationTicks: Int,
    val e0: Double, val e1: Double, val e2: Double, val e3: Double, val e4: Double
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleTranslatePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_translate")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleTranslatePayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleTranslatePayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleTranslatePayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val px = buf.readDouble()
                    val py = buf.readDouble()
                    val pz = buf.readDouble()
                    val ox = buf.readDouble()
                    val oy = buf.readDouble()
                    val oz = buf.readDouble()
                    val tx = buf.readDouble()
                    val ty = buf.readDouble()
                    val tz = buf.readDouble()
                    val dur = ByteBufCodecs.VAR_INT.decode(buf)
                    val e0 = buf.readDouble()
                    val e1 = buf.readDouble()
                    val e2 = buf.readDouble()
                    val e3 = buf.readDouble()
                    val e4 = buf.readDouble()
                    return ParticleTranslatePayload(id, px, py, pz, ox, oy, oz, tx, ty, tz, dur, e0, e1, e2, e3, e4)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleTranslatePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeDouble(p.px)
                    buf.writeDouble(p.py)
                    buf.writeDouble(p.pz)
                    buf.writeDouble(p.ox)
                    buf.writeDouble(p.oy)
                    buf.writeDouble(p.oz)
                    buf.writeDouble(p.tx)
                    buf.writeDouble(p.ty)
                    buf.writeDouble(p.tz)
                    ByteBufCodecs.VAR_INT.encode(buf, p.durationTicks)
                    buf.writeDouble(p.e0)
                    buf.writeDouble(p.e1)
                    buf.writeDouble(p.e2)
                    buf.writeDouble(p.e3)
                    buf.writeDouble(p.e4)
                }
            }

        fun of(id: UUID, pivot: Vec3, offset: Vec3, delta: Vec3,
               durationTicks: Int, easing: EasingType): ParticleTranslatePayload {
            val ser = easing.serialize()
            return ParticleTranslatePayload(
                id, pivot.x, pivot.y, pivot.z,
                offset.x, offset.y, offset.z,
                delta.x, delta.y, delta.z,
                durationTicks, ser[0], ser[1], ser[2], ser[3], ser[4]
            )
        }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun easingType(): EasingType = EasingType.deserialize(doubleArrayOf(e0, e1, e2, e3, e4))
}
