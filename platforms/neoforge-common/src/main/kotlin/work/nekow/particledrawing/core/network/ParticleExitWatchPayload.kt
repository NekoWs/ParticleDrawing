package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/**
 * 服务端 → 客户端：观察一批粒子的尺寸退场终点。
 *
 * 大组按 [MAX_BATCH] 分段；同一 [exitId] + [version] 的段会合并，只有 [last] 到达后
 * 客户端才开始判定完成。重定向会带更大的 version，旧段与旧完成信号都作废。
 */
data class ParticleExitWatchPayload(
    val exitId: UUID,
    val version: Int,
    val durationTicks: Int,
    val particleIds: List<UUID>,
    val last: Boolean,
) : CustomPacketPayload {

    init {
        requireLocalized(particleIds.size <= MAX_BATCH) { translation("error.packet.exit_limit", particleIds.size, MAX_BATCH) }
        requireLocalized(durationTicks >= 0) { translation("error.packet.exit_duration") }
    }

    companion object {
        const val MAX_BATCH = 512

        @JvmField
        val TYPE = CustomPacketPayload.Type<ParticleExitWatchPayload>(
            Identifier.fromNamespaceAndPath("particledrawing", "particle_exit_watch")
        )

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleExitWatchPayload> =
            object : StreamCodec<FriendlyByteBuf, ParticleExitWatchPayload> {
                override fun decode(buf: FriendlyByteBuf): ParticleExitWatchPayload {
                    val exitId = StreamCodecs.UUID_CODEC.decode(buf)
                    val version = ByteBufCodecs.VAR_INT.decode(buf)
                    val duration = ByteBufCodecs.VAR_INT.decode(buf)
                    val count = ByteBufCodecs.VAR_INT.decode(buf)
                    requireLocalized(count in 0..MAX_BATCH) { translation("error.packet.exit_limit", count, MAX_BATCH) }
                    val ids = ArrayList<UUID>(count)
                    repeat(count) { ids.add(StreamCodecs.UUID_CODEC.decode(buf)) }
                    val last = buf.readBoolean()
                    return ParticleExitWatchPayload(exitId, version, duration, ids, last)
                }

                override fun encode(buf: FriendlyByteBuf, p: ParticleExitWatchPayload) {
                    requireLocalized(p.particleIds.size <= MAX_BATCH) {
                        translation("error.packet.exit_limit", p.particleIds.size, MAX_BATCH)
                    }
                    StreamCodecs.UUID_CODEC.encode(buf, p.exitId)
                    ByteBufCodecs.VAR_INT.encode(buf, p.version)
                    ByteBufCodecs.VAR_INT.encode(buf, p.durationTicks)
                    ByteBufCodecs.VAR_INT.encode(buf, p.particleIds.size)
                    for (id in p.particleIds) StreamCodecs.UUID_CODEC.encode(buf, id)
                    buf.writeBoolean(p.last)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
