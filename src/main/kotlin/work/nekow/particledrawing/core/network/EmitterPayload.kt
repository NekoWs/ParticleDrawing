package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.EmitMode
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleVisual
import java.util.UUID

/**
 * 发射器的静态参数：**只在声明时下发一次**（改动走 [EmitterUpdatePayload]）。
 *
 * @param mode 按里程还是按时间发射
 * @param spacing 按里程口径：每多少格一颗
 * @param intervalMs 按时间口径：每多少毫秒一颗
 * @param lifetimeTicks 每颗粒子的寿命（tick）
 * @param scale 每颗粒子的缩放（编辑器单位）
 * @param visual 生成时定死的外观（贴图 / UV / 各向异性 / 朝向 / 加色）
 * @param lifeCurve 每颗粒子的寿命曲线
 * @param velocity 出生速度（blocks/tick）
 * @param maxAlive 单个发射器同时存活的粒子上限
 */
data class EmitterParams(
    val mode: EmitMode,
    val spacing: Double,
    val intervalMs: Int,
    val lifetimeTicks: Int,
    val r: Float, val g: Float, val b: Float, val a: Float,
    val scale: Float,
    val visual: ParticleVisual?,
    val lifeCurve: ParticleLifeCurve?,
    val velocity: Vec3,
    val glowing: Boolean,
    val lightLevel: Int,
    val maxAlive: Int,
)

internal object EmitterParamsCodec {

    fun write(buf: FriendlyByteBuf, p: EmitterParams) {
        buf.writeVarInt(p.mode.ordinal)
        buf.writeDouble(p.spacing)
        buf.writeVarInt(p.intervalMs)
        buf.writeVarInt(p.lifetimeTicks)
        buf.writeFloat(p.r); buf.writeFloat(p.g); buf.writeFloat(p.b); buf.writeFloat(p.a)
        buf.writeFloat(p.scale)
        ParticleVisualCodec.write(buf, p.visual)
        ParticleCurveCodec.write(buf, p.lifeCurve)
        StreamCodecs.writeVec(buf, p.velocity)
        buf.writeBoolean(p.glowing)
        buf.writeVarInt(p.lightLevel)
        buf.writeVarInt(p.maxAlive)
    }

    fun read(buf: FriendlyByteBuf): EmitterParams = EmitterParams(
        mode = if (buf.readVarInt() == EmitMode.TIME.ordinal) EmitMode.TIME else EmitMode.DISTANCE,
        spacing = buf.readDouble(),
        intervalMs = buf.readVarInt(),
        lifetimeTicks = buf.readVarInt(),
        r = buf.readFloat(), g = buf.readFloat(), b = buf.readFloat(), a = buf.readFloat(),
        scale = buf.readFloat(),
        visual = ParticleVisualCodec.read(buf),
        lifeCurve = ParticleCurveCodec.read(buf),
        velocity = StreamCodecs.readVec(buf),
        glowing = buf.readBoolean(),
        lightLevel = buf.readVarInt(),
        maxAlive = buf.readVarInt(),
    )
}

/**
 * 声明一个运行时发射器：服务端只说一次「沿哪个锚点、按什么口径、发什么样的粒子」，
 * 客户端按渲染帧自己推进里程/时间并生成粒子（见 [EmitterHandle]）。
 */
data class EmitterSpawnPayload(
    val emitterId: UUID,
    val anchor: Anchor,
    val params: EmitterParams,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<EmitterSpawnPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "emitter_spawn")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EmitterSpawnPayload> =
            object : StreamCodec<FriendlyByteBuf, EmitterSpawnPayload> {
                override fun decode(buf: FriendlyByteBuf): EmitterSpawnPayload =
                    EmitterSpawnPayload(
                        StreamCodecs.UUID_CODEC.decode(buf),
                        StreamCodecs.readAnchor(buf),
                        EmitterParamsCodec.read(buf),
                    )

                override fun encode(buf: FriendlyByteBuf, p: EmitterSpawnPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.emitterId)
                    StreamCodecs.writeAnchor(buf, p.anchor)
                    EmitterParamsCodec.write(buf, p.params)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * 发射器运行期变更（锚点挪了 / 密度改了）：整份可变旋钮一起下发，客户端整份替换——
 * 只改一项时其余项与声明时同值，不会出现「部分更新叠加出错」。
 */
data class EmitterUpdatePayload(
    val emitterId: UUID,
    val anchor: Anchor,
    val mode: EmitMode,
    val spacing: Double,
    val intervalMs: Int,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<EmitterUpdatePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "emitter_update")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EmitterUpdatePayload> =
            object : StreamCodec<FriendlyByteBuf, EmitterUpdatePayload> {
                override fun decode(buf: FriendlyByteBuf): EmitterUpdatePayload =
                    EmitterUpdatePayload(
                        StreamCodecs.UUID_CODEC.decode(buf),
                        StreamCodecs.readAnchor(buf),
                        if (buf.readVarInt() == EmitMode.TIME.ordinal) EmitMode.TIME else EmitMode.DISTANCE,
                        buf.readDouble(),
                        buf.readVarInt(),
                    )

                override fun encode(buf: FriendlyByteBuf, p: EmitterUpdatePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.emitterId)
                    StreamCodecs.writeAnchor(buf, p.anchor)
                    buf.writeVarInt(p.mode.ordinal)
                    buf.writeDouble(p.spacing)
                    buf.writeVarInt(p.intervalMs)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** 停掉一个发射器：已生成的粒子不受影响，各自走完寿命。 */
data class EmitterStopPayload(
    val emitterId: UUID,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<EmitterStopPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "emitter_stop")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EmitterStopPayload> =
            object : StreamCodec<FriendlyByteBuf, EmitterStopPayload> {
                override fun decode(buf: FriendlyByteBuf): EmitterStopPayload =
                    EmitterStopPayload(StreamCodecs.UUID_CODEC.decode(buf))

                override fun encode(buf: FriendlyByteBuf, p: EmitterStopPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.emitterId)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
