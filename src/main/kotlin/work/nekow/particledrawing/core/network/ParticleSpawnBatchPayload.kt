package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * 批量粒子生成：一次包下发整批 [entries]（一条尾迹/一次爆发不必逐颗发包）。
 *
 * 每条记录与 [ParticleSpawnPayload] 布局一致（含寿命曲线与首帧插值端点），
 * 因此批量生成与逐颗生成在客户端落地完全同一条路径。
 *
 * @param entries 生成记录；服务端按可见性逐玩家裁剪，单包条数不超过 [MAX_BATCH]
 */
@Suppress("unused")
data class ParticleSpawnBatchPayload(
    val entries: List<ParticleSpawnPayload>,
) : CustomPacketPayload {

    init {
        require(entries.size <= MAX_BATCH) { "批量生成条数越界: ${entries.size} > $MAX_BATCH" }
    }

    companion object {
        /** 单包条数上限（服务端超限时拆包发送）。 */
        const val MAX_BATCH = 256

        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleSpawnBatchPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_spawn_batch")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleSpawnBatchPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleSpawnBatchPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleSpawnBatchPayload {
                    val count = buf.readVarInt()
                    require(count in 0..MAX_BATCH) { "批量生成条数越界: $count" }
                    val entries = ArrayList<ParticleSpawnPayload>(count)
                    for (i in 0 until count) entries.add(ParticleSpawnPayload.readFrom(buf))
                    return ParticleSpawnBatchPayload(entries)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleSpawnBatchPayload) {
                    buf.writeVarInt(p.entries.size)
                    for (entry in p.entries) entry.writeTo(buf)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
