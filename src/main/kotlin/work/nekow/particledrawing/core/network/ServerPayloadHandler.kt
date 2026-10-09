package work.nekow.particledrawing.core.network

import net.minecraft.network.Connection
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.api.EffectRegistry
import work.nekow.particledrawing.core.server.AnimationSyncConfigTask
import work.nekow.particledrawing.core.server.AnimationSyncService
import work.nekow.particledrawing.core.server.ServerProgramCompletion
import java.util.Collections
import java.util.WeakHashMap

/**
 * 服务器端载荷处理器：处理客户端上报的同步请求、特效字节请求与编排完成信号。
 */
internal object ServerPayloadHandler {

    /** 已处理过同步请求的连接；弱引用键，连接断开后可被回收。 */
    private val handledConnections = Collections.newSetFromMap(
        Collections.synchronizedMap(WeakHashMap<Connection, Boolean>())
    )

    // 客户端「动画同步请求」：比对差异，分块下发缺失或变化的文件，最后发完成信号并结束配置任务。
    // 内存连接（单机 / LAN）与客户端共享目录，直接回完成信号；重复请求忽略。
    fun handleSyncRequest(payload: AnimationSyncRequestPayload, context: IPayloadContext) {
        context.enqueueWork {
            val connection = context.connection()
            if (!handledConnections.add(connection)) return@enqueueWork
            if (connection.isMemoryConnection()) {
                context.reply(AnimationSyncDonePayload(0))
                context.finishCurrentTask(AnimationSyncConfigTask.TYPE)
                return@enqueueWork
            }
            val diff = AnimationSyncService.computeDiff(payload.hashes)
            for (file in diff) {
                sendFile(context, file.name, file.bytes)
            }
            context.reply(AnimationSyncDonePayload(diff.size))
            // 配置任务收尾，让连接进入下一阶段
            context.finishCurrentTask(AnimationSyncConfigTask.TYPE)
        }
    }

    /**
     * 处理客户端「特效字节请求」：客户端缓存缺失时按 key 下发注册表里的 .pdrawc 字节。
     */
    fun handleEffectRequest(payload: EffectRequestPayload, context: IPayloadContext) {
        context.enqueueWork {
            val player = context.player() as? ServerPlayer ?: return@enqueueWork
            val data = EffectRegistry.dataFor(payload.key) ?: return@enqueueWork
            PacketDistributor.sendToPlayer(player, EffectDataPayload(payload.key, data))
        }
    }

    /**
     * 客户端「编排动画跑完了」：触发注册的完成回调，登记了 retire 时在余量后销毁整组。
     * 多个客户端各自上报，第一个到达即生效。
     */
    fun handleProgramComplete(payload: ProgramCompletePayload, context: IPayloadContext) {
        context.enqueueWork {
            ServerProgramCompletion.complete(payload.programId)
        }
    }

    private fun sendFile(context: IPayloadContext, name: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            context.reply(AnimationSyncFilePayload(name, true, ByteArray(0)))
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(AnimationSyncFilePayload.CHUNK_SIZE, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + len)
            val eof = offset + len >= bytes.size
            context.reply(AnimationSyncFilePayload(name, eof, chunk))
            offset += len
        }
    }
}
