package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
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
    val lightLevel: Int
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleSpawnPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_spawn")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleSpawnPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleSpawnPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleSpawnPayload {
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
                    return ParticleSpawnPayload(pid, x, y, z, r, g, b, a, scale, lifetime, gid, glw, light)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleSpawnPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.particleId)
                    buf.writeDouble(p.x)
                    buf.writeDouble(p.y)
                    buf.writeDouble(p.z)
                    buf.writeFloat(p.r)
                    buf.writeFloat(p.g)
                    buf.writeFloat(p.b)
                    buf.writeFloat(p.a)
                    buf.writeFloat(p.scale)
                    buf.writeVarInt(p.lifetime)
                    StreamCodecs.writeNullableUUID(buf, p.groupId)
                    buf.writeBoolean(p.glowing)
                    buf.writeVarInt(p.lightLevel)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun position(): Vec3 = Vec3(x, y, z)

    fun color(): Color = Color.of(r, g, b, a)
}
