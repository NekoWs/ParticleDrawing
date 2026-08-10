package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * 组变换数据包，对整组粒子应用平移/旋转/变色/缩放。
 *
 * @param groupId 组 ID
 * @param transformType 变换类型（0=平移, 1=旋转, 2=变色, 3=缩放）
 * @param dx/dy/dz 平移增量
 * @param ax/ay/az 旋转轴
 * @param radians 旋转弧度
 * @param r/g/b/a 目标颜色
 * @param targetScale 目标缩放
 * @param px/py/pz 变换轴心
 * @param durationTicks 过渡持续 tick 数
 * @param e0-e4 缓动曲线序列化参数
 */
data class ParticleGroupTransformPayload(
    val groupId: UUID,
    val transformType: Int,
    val dx: Double, val dy: Double, val dz: Double,
    val ax: Double, val ay: Double, val az: Double,
    val radians: Double,
    val r: Float, val g: Float, val b: Float, val a: Float,
    val targetScale: Float,
    val px: Double, val py: Double, val pz: Double,
    val durationTicks: Int,
    val e0: Double, val e1: Double, val e2: Double, val e3: Double, val e4: Double
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleGroupTransformPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "group_transform")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleGroupTransformPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleGroupTransformPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleGroupTransformPayload {
                    val gid = StreamCodecs.UUID_CODEC.decode(buf)
                    val tt = ByteBufCodecs.VAR_INT.decode(buf)
                    val dx = buf.readDouble()
                    val dy = buf.readDouble()
                    val dz = buf.readDouble()
                    val ax = buf.readDouble()
                    val ay = buf.readDouble()
                    val az = buf.readDouble()
                    val rad = buf.readDouble()
                    val r = buf.readFloat()
                    val g = buf.readFloat()
                    val b = buf.readFloat()
                    val a = buf.readFloat()
                    val ts = buf.readFloat()
                    val px = buf.readDouble()
                    val py = buf.readDouble()
                    val pz = buf.readDouble()
                    val dur = ByteBufCodecs.VAR_INT.decode(buf)
                    val e0 = buf.readDouble()
                    val e1 = buf.readDouble()
                    val e2 = buf.readDouble()
                    val e3 = buf.readDouble()
                    val e4 = buf.readDouble()
                    return ParticleGroupTransformPayload(
                        gid, tt, dx, dy, dz, ax, ay, az, rad, r, g, b, a, ts, px, py, pz, dur, e0, e1, e2, e3, e4)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleGroupTransformPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.groupId)
                    ByteBufCodecs.VAR_INT.encode(buf, p.transformType)
                    buf.writeDouble(p.dx); buf.writeDouble(p.dy); buf.writeDouble(p.dz)
                    buf.writeDouble(p.ax); buf.writeDouble(p.ay); buf.writeDouble(p.az)
                    buf.writeDouble(p.radians)
                    buf.writeFloat(p.r); buf.writeFloat(p.g); buf.writeFloat(p.b); buf.writeFloat(p.a)
                    buf.writeFloat(p.targetScale)
                    buf.writeDouble(p.px); buf.writeDouble(p.py); buf.writeDouble(p.pz)
                    ByteBufCodecs.VAR_INT.encode(buf, p.durationTicks)
                    buf.writeDouble(p.e0); buf.writeDouble(p.e1); buf.writeDouble(p.e2)
                    buf.writeDouble(p.e3); buf.writeDouble(p.e4)
                }
            }

        fun translate(groupId: UUID,
                      dx: Double, dy: Double, dz: Double,
                      px: Double, py: Double, pz: Double,
                      durationTicks: Int, easing: EasingType): ParticleGroupTransformPayload {
            val ser = easing.serialize()
            return ParticleGroupTransformPayload(groupId, 0,
                dx, dy, dz, 0.0, 0.0, 0.0, 0.0, 0f, 0f, 0f, 0f, 0f,
                px, py, pz, durationTicks,
                ser[0], ser[1], ser[2], ser[3], ser[4])
        }

        fun rotate(groupId: UUID,
                   ax: Double, ay: Double, az: Double,
                   radians: Double,
                   px: Double, py: Double, pz: Double,
                   durationTicks: Int, easing: EasingType): ParticleGroupTransformPayload {
            val ser = easing.serialize()
            return ParticleGroupTransformPayload(groupId, 1,
                0.0, 0.0, 0.0, ax, ay, az, radians, 0f, 0f, 0f, 0f, 0f,
                px, py, pz, durationTicks,
                ser[0], ser[1], ser[2], ser[3], ser[4])
        }

        fun recolor(groupId: UUID,
                    r: Float, g: Float, b: Float, a: Float,
                    durationTicks: Int, easing: EasingType): ParticleGroupTransformPayload {
            val ser = easing.serialize()
            return ParticleGroupTransformPayload(groupId, 2,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, r, g, b, a, 0f,
                0.0, 0.0, 0.0, durationTicks,
                ser[0], ser[1], ser[2], ser[3], ser[4])
        }

        fun scale(groupId: UUID, targetScale: Float,
                  px: Double, py: Double, pz: Double,
                  durationTicks: Int, easing: EasingType): ParticleGroupTransformPayload {
            val ser = easing.serialize()
            return ParticleGroupTransformPayload(groupId, 3,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0f, 0f, 0f, 0f, targetScale,
                px, py, pz, durationTicks,
                ser[0], ser[1], ser[2], ser[3], ser[4])
        }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun easingType(): EasingType = EasingType.deserialize(doubleArrayOf(e0, e1, e2, e3, e4))
}
