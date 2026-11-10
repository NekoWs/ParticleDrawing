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
 * 发射器的静态参数：只在声明时下发一次，改动走 [EmitterUpdatePayload]。
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
 * @param jitter 垂直运动方向的随机偏移半径（格）；0 = 不抖
 * @param offsetAlong 沿运动方向的偏移（格，正 = 往前）
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
    val jitter: Double = 0.0,
    val offsetAlong: Double = 0.0,
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
        buf.writeDouble(p.jitter)
        buf.writeDouble(p.offsetAlong)
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
        jitter = buf.readDouble(),
        offsetAlong = buf.readDouble(),
    )
}

/**
 * 声明一个运行时发射器：服务端下发一次声明，客户端按渲染帧沿锚点推进时间或里程并生成粒子（见 [EmitterHandle]）。
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
 * 发射器运行期变更：按段更新，每次只带改动的部分。
 *
 * 三段分别是 [Anchor]（锚点）、[EmitterCadence]（发射口径）与 [params]（整份替换的静态参数）。
 */
data class EmitterUpdatePayload(
    val emitterId: UUID,
    val anchor: Anchor? = null,
    val cadence: EmitterCadence? = null,
    val params: EmitterParams? = null,
) : CustomPacketPayload {

    /** 发射口径：按里程的格数或按时间的毫秒数。 */
    data class EmitterCadence(val mode: EmitMode, val spacing: Double, val intervalMs: Int)

    companion object {
        private const val FLAG_ANCHOR = 1 shl 0
        private const val FLAG_CADENCE = 1 shl 1
        private const val FLAG_PARAMS = 1 shl 2

        @JvmField
        val TYPE = CustomPacketPayload.Type<EmitterUpdatePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "emitter_update")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, EmitterUpdatePayload> =
            object : StreamCodec<FriendlyByteBuf, EmitterUpdatePayload> {
                override fun decode(buf: FriendlyByteBuf): EmitterUpdatePayload {
                    val emitterId = StreamCodecs.UUID_CODEC.decode(buf)
                    val flags = buf.readVarInt()
                    val anchor = if (flags and FLAG_ANCHOR != 0) StreamCodecs.readAnchor(buf) else null
                    val cadence = if (flags and FLAG_CADENCE != 0) {
                        EmitterCadence(
                            if (buf.readVarInt() == EmitMode.TIME.ordinal) EmitMode.TIME else EmitMode.DISTANCE,
                            buf.readDouble(),
                            buf.readVarInt(),
                        )
                    } else null
                    val params = if (flags and FLAG_PARAMS != 0) EmitterParamsCodec.read(buf) else null
                    return EmitterUpdatePayload(emitterId, anchor, cadence, params)
                }

                override fun encode(buf: FriendlyByteBuf, p: EmitterUpdatePayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.emitterId)
                    var flags = 0
                    if (p.anchor != null) flags = flags or FLAG_ANCHOR
                    if (p.cadence != null) flags = flags or FLAG_CADENCE
                    if (p.params != null) flags = flags or FLAG_PARAMS
                    buf.writeVarInt(flags)
                    p.anchor?.let { StreamCodecs.writeAnchor(buf, it) }
                    p.cadence?.let {
                        buf.writeVarInt(it.mode.ordinal)
                        buf.writeDouble(it.spacing)
                        buf.writeVarInt(it.intervalMs)
                    }
                    p.params?.let { EmitterParamsCodec.write(buf, it) }
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
