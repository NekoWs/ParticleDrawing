package work.nekow.particledrawing.core.motion

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.ParticleDrawing
import java.util.UUID

data class MotionPayload(
    val groupId: UUID,
    val active: Boolean,
    val algorithmId: String,
    val params: DoubleArray,
    val px: Double, val py: Double, val pz: Double
) : CustomPacketPayload {

    companion object {
        val TYPE = CustomPacketPayload.Type<MotionPayload>(
            Identifier.fromNamespaceAndPath(ParticleDrawing.MODID, "motion")
        )

        val STREAM_CODEC = object : StreamCodec<FriendlyByteBuf, MotionPayload> {
            override fun decode(buf: FriendlyByteBuf): MotionPayload {
                val gid = UUID(buf.readLong(), buf.readLong())
                val active = buf.readBoolean()
                val algoId = ByteBufCodecs.STRING_UTF8.decode(buf)
                val count = buf.readVarInt()
                val params = DoubleArray(count) { buf.readDouble() }
                val px = buf.readDouble()
                val py = buf.readDouble()
                val pz = buf.readDouble()
                return MotionPayload(gid, active, algoId, params, px, py, pz)
            }

            override fun encode(buf: FriendlyByteBuf, p: MotionPayload) {
                buf.writeLong(p.groupId.mostSignificantBits)
                buf.writeLong(p.groupId.leastSignificantBits)
                buf.writeBoolean(p.active)
                ByteBufCodecs.STRING_UTF8.encode(buf, p.algorithmId)
                buf.writeVarInt(p.params.size)
                for (v in p.params) buf.writeDouble(v)
                buf.writeDouble(p.px); buf.writeDouble(p.py); buf.writeDouble(p.pz)
            }
        }
    }

    override fun type() = TYPE
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MotionPayload

        if (active != other.active) return false
        if (px != other.px) return false
        if (py != other.py) return false
        if (pz != other.pz) return false
        if (groupId != other.groupId) return false
        if (algorithmId != other.algorithmId) return false
        if (!params.contentEquals(other.params)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = active.hashCode()
        result = 31 * result + px.hashCode()
        result = 31 * result + py.hashCode()
        result = 31 * result + pz.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + algorithmId.hashCode()
        result = 31 * result + params.contentHashCode()
        return result
    }
}
