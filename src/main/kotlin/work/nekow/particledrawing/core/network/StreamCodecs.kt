package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import java.util.UUID

/**
 * 网络流编解码工具集，提供 UUID 和非空类型 UUID 的读写方法。
 */
internal object StreamCodecs {

    val UUID_CODEC: StreamCodec<FriendlyByteBuf, UUID> =
        object : StreamCodec<FriendlyByteBuf, UUID> {
            override fun decode(buf: FriendlyByteBuf): UUID =
                UUID(buf.readLong(), buf.readLong())

            override fun encode(buf: FriendlyByteBuf, id: UUID) {
                buf.writeLong(id.mostSignificantBits)
                buf.writeLong(id.leastSignificantBits)
            }
        }

    fun writeNullableUUID(buf: FriendlyByteBuf, id: UUID?) {
        buf.writeBoolean(id != null)
        if (id != null) {
            buf.writeLong(id.mostSignificantBits)
            buf.writeLong(id.leastSignificantBits)
        }
    }

    fun readNullableUUID(buf: FriendlyByteBuf): UUID? {
        return if (buf.readBoolean()) UUID(buf.readLong(), buf.readLong()) else null
    }
}
