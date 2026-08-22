package work.nekow.particledrawing.core.network

import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.core.server.AnimationSyncConfigTask
import work.nekow.particledrawing.core.server.AnimationSyncService

/**
 * 服务器端 payload 处理器：处理客户端 → 服务器的配置阶段请求（动画文件同步）。
 */
internal object ServerPayloadHandler {

    /**
     * 处理客户端「动画同步请求」：对比差异，将缺失/变化文件分块下发，最后发完成信号
     * 并通知服务端完成当前配置任务（客户端禁止调用 finishCurrentTask）。
     */
    fun handleSyncRequest(payload: AnimationSyncRequestPayload, context: IPayloadContext) {
        context.enqueueWork {
            val diff = AnimationSyncService.computeDiff(payload.hashes)
            for (file in diff) {
                sendFile(context, file.name, file.bytes)
            }
            context.reply(AnimationSyncDonePayload(diff.size))
            // 服务端配置任务收尾：让连接进入下一阶段，避免「正在加载地形」卡死
            context.finishCurrentTask(AnimationSyncConfigTask.TYPE)
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
