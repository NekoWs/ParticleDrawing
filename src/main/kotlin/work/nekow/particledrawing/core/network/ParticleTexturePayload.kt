package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 服务器 → 客户端「程序化贴图内容块」（playToClient）。
 *
 * 大贴图按 [CHUNK_SIZE] 拆分逐块发送，客户端按 [id] 累积，收到 `eof=true` 的块后解码注册。
 * 进服时服务器把已登记的全部贴图推一遍，运行中新登记的立即广播（见 `TextureSyncService`）。
 *
 * @param id 贴图协议 id（与逐粒子载荷里引用的是同一个 id）
 * @param name 贴图名（客户端按名解码缓存，重名同名同图才复用）
 * @param eof 是否为本贴图最后一块
 * @param data 本块字节内容（PNG）
 */
@Suppress("unused")
data class ParticleTexturePayload(
    val id: Int,
    val name: String,
    val eof: Boolean,
    val data: ByteArray,
) : CustomPacketPayload {

    companion object {
        /** 单包字节上限（约 32 KiB）：贴图通常几 KB，留足够余量给协议包体。 */
        const val CHUNK_SIZE: Int = 32 * 1024

        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleTexturePayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_texture")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleTexturePayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleTexturePayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleTexturePayload {
                    val id = buf.readVarInt()
                    val name = buf.readUtf()
                    val eof = buf.readBoolean()
                    val data = buf.readByteArray()
                    return ParticleTexturePayload(id, name, eof, data)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleTexturePayload) {
                    buf.writeVarInt(p.id)
                    buf.writeUtf(p.name)
                    buf.writeBoolean(p.eof)
                    buf.writeByteArray(p.data)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
