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
 * 粒子设置位置数据包（组 set 位置轨道）：把未旋转偏移 [offset]（相对轴心）缓动到新值，
 * 保留旋转、清零平移增量，最终位置 = pivot + rotate(offset, rot)。
 *
 * @param particleId 粒子 ID
 * @param px/py/pz 旋转轴心（绝对世界坐标）
 * @param ox/oy/oz 目标未旋转偏移（相对轴心）
 * @param durationTicks 过渡持续 tick 数
 * @param e0-e4 缓动曲线序列化参数
 */
data class ParticleSetPositionPayload(
    val particleId: UUID,
    val px: Double, val py: Double, val pz: Double,
    val ox: Double, val oy: Double, val oz: Double,
    val durationTicks: Int,
    val e0: Double, val e1: Double, val e2: Double, val e3: Double, val e4: Double
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleSetPositionPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_set_position")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleSetPositionPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleSetPositionPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleSetPositionPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val px = buf.readDouble()
                    val py = buf.readDouble()
                    val pz = buf.readDouble()
                    val ox = buf.readDouble()
                    val oy = buf.readDouble()
                    val oz = buf.readDouble()
                    val dur = ByteBufCodecs.VAR_INT.decode(buf)
                    val e0 = buf.readDouble()
                    val e1 = buf.readDouble()
                    val e2 = buf.readDouble()
                    val e3 = buf.readDouble()
                    val e4 = buf.readDouble()
                    return ParticleSetPositionPayload(id, px, py, pz, ox, oy, oz, dur, e0, e1, e2, e3, e4)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleSetPositionPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeDouble(p.px)
                    buf.writeDouble(p.py)
                    buf.writeDouble(p.pz)
                    buf.writeDouble(p.ox)
                    buf.writeDouble(p.oy)
                    buf.writeDouble(p.oz)
                    ByteBufCodecs.VAR_INT.encode(buf, p.durationTicks)
                    buf.writeDouble(p.e0)
                    buf.writeDouble(p.e1)
                    buf.writeDouble(p.e2)
                    buf.writeDouble(p.e3)
                    buf.writeDouble(p.e4)
                }
            }

        fun of(id: UUID, pivot: Vec3, offset: Vec3,
               durationTicks: Int, easing: EasingType): ParticleSetPositionPayload {
            val ser = easing.serialize()
            return ParticleSetPositionPayload(
                id, pivot.x, pivot.y, pivot.z,
                offset.x, offset.y, offset.z,
                durationTicks, ser[0], ser[1], ser[2], ser[3], ser[4]
            )
        }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun easingType(): EasingType = EasingType.deserialize(doubleArrayOf(e0, e1, e2, e3, e4))
}
