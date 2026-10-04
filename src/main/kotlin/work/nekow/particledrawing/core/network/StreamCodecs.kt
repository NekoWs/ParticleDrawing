package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.Orient
import java.util.UUID

/**
 * 网络流编解码工具集，提供 UUID、Identifier 的读写方法。
 */
internal object StreamCodecs {

    fun writeIdentifier(buf: FriendlyByteBuf, id: Identifier) {
        buf.writeUtf(id.namespace)
        buf.writeUtf(id.path)
    }

    fun readIdentifier(buf: FriendlyByteBuf): Identifier =
        Identifier.fromNamespaceAndPath(buf.readUtf(), buf.readUtf())

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

    fun writeVec(buf: FriendlyByteBuf, v: Vec3) {
        buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z)
    }

    fun readVec(buf: FriendlyByteBuf): Vec3 = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())

    /** 锚点编解码（特效播放与发射器共用同一套布局）。 */
    fun writeAnchor(buf: FriendlyByteBuf, anchor: Anchor) {
        when (anchor) {
            is Anchor.Fixed -> {
                buf.writeByte(0)
                writeVec(buf, anchor.pos)
            }
            is Anchor.Entity -> {
                buf.writeByte(1)
                buf.writeVarInt(anchor.entityId)
                writeVec(buf, anchor.offset)
            }
            is Anchor.Movable -> {
                buf.writeByte(2)
                writeVec(buf, anchor.pos)
                writeVec(buf, anchor.velocity)
                buf.writeByte(if (anchor.orient == Orient.VELOCITY) 0 else 1)
            }
        }
    }

    fun readAnchor(buf: FriendlyByteBuf): Anchor = when (buf.readByte().toInt()) {
        0 -> Anchor.Fixed(readVec(buf))
        1 -> Anchor.Entity(buf.readVarInt(), readVec(buf))
        2 -> Anchor.Movable(readVec(buf), readVec(buf), if (buf.readByte().toInt() == 0) Orient.VELOCITY else Orient.WORLD)
        else -> Anchor.Fixed(Vec3.ZERO)
    }
}
