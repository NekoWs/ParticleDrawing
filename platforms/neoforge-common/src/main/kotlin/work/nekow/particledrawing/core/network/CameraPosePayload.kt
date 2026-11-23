package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 自由镜头姿态：`/pdraw camera` 把目标位置、朝向和 FOV 发给客户端，由客户端逐渲染帧向目标插值。
 * `active = false` 表示恢复玩家视角。
 */
data class CameraPosePayload(
    val active: Boolean,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val fov: Float,
    val smooth: Float,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<CameraPosePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "camera_pose"),
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CameraPosePayload> =
            object : StreamCodec<FriendlyByteBuf, CameraPosePayload> {
                override fun decode(buf: FriendlyByteBuf): CameraPosePayload = CameraPosePayload(
                    buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                )

                override fun encode(buf: FriendlyByteBuf, payload: CameraPosePayload) {
                    buf.writeBoolean(payload.active)
                    buf.writeDouble(payload.x)
                    buf.writeDouble(payload.y)
                    buf.writeDouble(payload.z)
                    buf.writeFloat(payload.yaw)
                    buf.writeFloat(payload.pitch)
                    buf.writeFloat(payload.fov)
                    buf.writeFloat(payload.smooth)
                }
            }

        fun stop(): CameraPosePayload = CameraPosePayload(false, 0.0, 0.0, 0.0, 0.0f, 0.0f, 0.0f, 0.0f)
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
