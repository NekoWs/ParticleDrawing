package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.server.ServerLifecycleHooks
import work.nekow.particledrawing.core.TextureRegistry
import work.nekow.particledrawing.core.network.ParticleTexturePayload

/**
 * 服务器端程序化贴图下发：把登记在 [TextureRegistry] 里的 PNG 字节送到需要渲染它的客户端。
 *
 * 两条路径：
 * - 新登记时立即广播给在线玩家；
 * - 玩家进服时补发全部（登记发生在任何人进服之前是常态）。
 *
 * 内置形状（[work.nekow.particledrawing.api.ParticleStyle]）不走网络：两端各自生成同一份像素。
 */
object TextureSyncService {

    /** 新登记的贴图推给当前在线玩家；服务器未运行时什么也不做。 */
    @JvmStatic
    fun broadcast(entry: TextureRegistry.Entry) {
        val bytes = entry.bytes ?: return
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        for (player in server.playerList.players) {
            send(player, entry.id, entry.name, bytes)
        }
    }

    /** 进服补发：把所有已登记的贴图推给该玩家。 */
    @JvmStatic
    fun sendAll(player: ServerPlayer) {
        for (entry in TextureRegistry.allWithBytes()) {
            val bytes = entry.bytes ?: continue
            send(player, entry.id, entry.name, bytes)
        }
    }

    /** 分块下发一张贴图。 */
    private fun send(player: ServerPlayer, id: Int, name: String, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(ParticleTexturePayload.CHUNK_SIZE, bytes.size - offset)
            val eof = offset + len >= bytes.size
            PacketDistributor.sendToPlayer(
                player,
                ParticleTexturePayload(id, name, eof, bytes.copyOfRange(offset, offset + len)),
            )
            offset += len
        }
    }
}
