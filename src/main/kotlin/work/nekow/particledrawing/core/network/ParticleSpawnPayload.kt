package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleVisual
import java.util.UUID

/**
 * 粒子生成数据包，包含粒子的视觉和位置等所有属性。
 *
 * @param particleId 粒子唯一 ID
 * @param x/y/z 世界坐标
 * @param r/g/b/a RGBA 颜色分量
 * @param scale 渲染缩放
 * @param lifetime 存活 tick 数
 * @param groupId 所属组 ID，可为 null
 * @param glowing 是否发光
 * @param lightLevel 发光粒子向外发出的光照等级 (0-15)
 * @param visual 生成时定死的外观（贴图 / UV / 各向异性 / 朝向 / 加色）；null = 默认外观
 * @param lifeCurve 逐粒子寿命曲线（寿命内的颜色/尺寸乘数）；null = 恒定外观
 * @param prev 上一 tick 的位置，与 [x]/[y]/[z] 组成一段插值；客户端首帧按 partialTick 在两点之间扫掠；
 *   null = 直接在当前位置出生
 */
@Suppress("unused")
data class ParticleSpawnPayload(
    val particleId: UUID,
    val x: Double, val y: Double, val z: Double,
    val r: Float, val g: Float, val b: Float, val a: Float,
    val scale: Float,
    val lifetime: Int,
    val groupId: UUID?,
    val glowing: Boolean,
    val lightLevel: Int,
    val visual: ParticleVisual? = null,
    val lifeCurve: ParticleLifeCurve? = null,
    val prev: Vec3? = null,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleSpawnPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_spawn")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleSpawnPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleSpawnPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleSpawnPayload = readFrom(buf)
                override fun encode(buf: FriendlyByteBuf, p: ParticleSpawnPayload) = p.writeTo(buf)
            }

        /** 单条生成记录的编解码：单发包与 [ParticleSpawnBatchPayload] 共用同一段布局。 */
        fun readFrom(buf: FriendlyByteBuf): ParticleSpawnPayload {
            val pid = StreamCodecs.UUID_CODEC.decode(buf)
            val x = buf.readDouble()
            val y = buf.readDouble()
            val z = buf.readDouble()
            val r = buf.readFloat()
            val g = buf.readFloat()
            val b = buf.readFloat()
            val a = buf.readFloat()
            val scale = buf.readFloat()
            val lifetime = buf.readVarInt()
            val gid = StreamCodecs.readNullableUUID(buf)
            val glw = buf.readBoolean()
            val light = buf.readVarInt()
            val visual = ParticleVisualCodec.read(buf)
            val lifeCurve = ParticleCurveCodec.read(buf)
            val hasPrev = buf.readBoolean()
            val prev = if (hasPrev) Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) else null
            return ParticleSpawnPayload(
                pid, x, y, z, r, g, b, a, scale, lifetime, gid, glw, light, visual, lifeCurve, prev,
            )
        }
    }

    fun writeTo(buf: FriendlyByteBuf) {
        StreamCodecs.UUID_CODEC.encode(buf, particleId)
        buf.writeDouble(x)
        buf.writeDouble(y)
        buf.writeDouble(z)
        buf.writeFloat(r)
        buf.writeFloat(g)
        buf.writeFloat(b)
        buf.writeFloat(a)
        buf.writeFloat(scale)
        buf.writeVarInt(lifetime)
        StreamCodecs.writeNullableUUID(buf, groupId)
        buf.writeBoolean(glowing)
        buf.writeVarInt(lightLevel)
        ParticleVisualCodec.write(buf, visual)
        ParticleCurveCodec.write(buf, lifeCurve)
        buf.writeBoolean(prev != null)
        prev?.let {
            buf.writeDouble(it.x)
            buf.writeDouble(it.y)
            buf.writeDouble(it.z)
        }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun position(): Vec3 = Vec3(x, y, z)

    fun color(): Color = Color.of(r, g, b, a)
}
