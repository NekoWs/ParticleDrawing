package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.Authority
import work.nekow.particledrawing.api.EffectOptions
import work.nekow.particledrawing.api.Orient
import java.util.UUID

/**
 * 服务端下发「按 key 播放特效」：只携带注册表 key（不携带 .pdrawc 全文），
 * 客户端从本地缓存解析；缓存缺失时向服务端请求 [EffectDataPayload]。
 */
@Suppress("unused")
data class PlayEffectPayload(
    val playbackId: UUID,
    val key: Identifier,
    val anchor: Anchor,
    val options: EffectOptions,
    val startGameTick: Long,
) : CustomPacketPayload {

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<PlayEffectPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "play_effect")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, PlayEffectPayload> =
            object : StreamCodec<FriendlyByteBuf, PlayEffectPayload> {
                override fun decode(buf: FriendlyByteBuf): PlayEffectPayload {
                    val id = StreamCodecs.UUID_CODEC.decode(buf)
                    val key = StreamCodecs.readIdentifier(buf)
                    val anchor = readAnchor(buf)
                    val options = readOptions(buf)
                    val start = buf.readVarLong()
                    return PlayEffectPayload(id, key, anchor, options, start)
                }

                override fun encode(buf: FriendlyByteBuf, p: PlayEffectPayload) {
                    StreamCodecs.UUID_CODEC.encode(buf, p.playbackId)
                    StreamCodecs.writeIdentifier(buf, p.key)
                    writeAnchor(buf, p.anchor)
                    writeOptions(buf, p.options)
                    buf.writeVarLong(p.startGameTick)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

private fun writeAnchor(buf: FriendlyByteBuf, anchor: Anchor) {
    when (anchor) {
        is Anchor.Fixed -> {
            buf.writeByte(0)
            buf.writeDouble(anchor.pos.x); buf.writeDouble(anchor.pos.y); buf.writeDouble(anchor.pos.z)
        }
        is Anchor.Entity -> {
            buf.writeByte(1)
            buf.writeVarInt(anchor.entityId)
            buf.writeDouble(anchor.offset.x); buf.writeDouble(anchor.offset.y); buf.writeDouble(anchor.offset.z)
        }
        is Anchor.Movable -> {
            buf.writeByte(2)
            buf.writeDouble(anchor.pos.x); buf.writeDouble(anchor.pos.y); buf.writeDouble(anchor.pos.z)
            buf.writeDouble(anchor.velocity.x); buf.writeDouble(anchor.velocity.y); buf.writeDouble(anchor.velocity.z)
            buf.writeByte(if (anchor.orient == Orient.VELOCITY) 0 else 1)
        }
    }
}

private fun readAnchor(buf: FriendlyByteBuf): Anchor = when (buf.readByte().toInt()) {
    0 -> Anchor.Fixed(Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()))
    1 -> Anchor.Entity(
        buf.readVarInt(),
        Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
    )
    2 -> Anchor.Movable(
        Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
        Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
        if (buf.readByte().toInt() == 0) Orient.VELOCITY else Orient.WORLD,
    )
    else -> Anchor.Fixed(Vec3.ZERO)
}

private fun writeOptions(buf: FriendlyByteBuf, o: EffectOptions) {
    buf.writeFloat(o.scale())
    buf.writeBoolean(o.loop() != null)
    o.loop()?.let { buf.writeBoolean(it) }
    buf.writeDouble(o.speed())
    buf.writeDouble(o.startTick())
    buf.writeByte(if (o.authority() == Authority.SERVER) 1 else 0)
}

private fun readOptions(buf: FriendlyByteBuf): EffectOptions {
    val o = EffectOptions()
    o.scale(buf.readFloat())
    if (buf.readBoolean()) o.loop(buf.readBoolean())
    o.speed(buf.readDouble())
    o.startTick(buf.readDouble())
    o.authority(if (buf.readByte().toInt() == 1) Authority.SERVER else Authority.CLIENT_LOCAL)
    return o
}