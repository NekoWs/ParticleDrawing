package work.nekow.particledrawing.core.client

import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.PdrawcReader
import work.nekow.particledrawing.core.network.AnimationSyncRequestPayload
import work.nekow.particledrawing.core.server.AnimationSyncService
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 客户端动画文件同步管理器（配置阶段）。
 *
 * 状态机：
 * 1. 服务器配置任务发来「开始」信号 → 上报本地已有文件哈希（[reportLocalHashes]）；
 * 2. 逐块接收 [AnimationSyncFilePayload]，按文件名累积；
 * 3. 收到 [AnimationSyncDonePayload] → 把缓存的差异文件写入 animations/ 目录，
 *    服务端结束配置任务（客户端不再调用 finishCurrentTask）。
 */
object ClientAnimationSyncManager {

    /** 正在累积的文件内容（相对名 → 字节流）。 */
    private val pendingFiles = LinkedHashMap<String, ByteArrayOutputStream>()

    /** 会话开始：上报本地已有文件哈希。 */
    @JvmStatic
    fun onBegin(context: IPayloadContext) {
        pendingFiles.clear()
        val hashes = AnimationSyncService.computeLocalHashes(AnimationLoader.DIRECTORY)
        context.reply(AnimationSyncRequestPayload(hashes))
    }

    /** 接收一个文件块。 */
    @JvmStatic
    fun onFileChunk(name: String, eof: Boolean, data: ByteArray) {
        val out = pendingFiles.getOrPut(name) { ByteArrayOutputStream() }
        out.write(data)
        if (eof) {
            val bytes = out.toByteArray()
            // .pdrawc 落盘前验签，验签失败的文件不写入（下次进服会重新同步）
            if (!name.endsWith(".pdrawc") || PdrawcReader.verify(bytes)) {
                writeFile(name, bytes)
            }
            pendingFiles.remove(name)
        }
    }

    /** 同步完成：清理缓存，等待服务端结束配置任务（客户端禁止调用 finishCurrentTask）。 */
    @JvmStatic
    fun onDone(context: IPayloadContext) {
        pendingFiles.clear()
    }

    /** 把单个同步文件写入本地 animations/ 目录（覆盖；先建父目录）。 */
    private fun writeFile(name: String, bytes: ByteArray) {
        val rel = name.replace('\\', '/')
        if (!AnimationSyncService.sanitizeRelativeName(rel)) return
        val target: Path = AnimationLoader.DIRECTORY.resolve(rel).normalize()
        if (!target.startsWith(AnimationLoader.DIRECTORY)) return
        try {
            Files.createDirectories(target.parent)
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        } catch (_: Exception) {
            // 写盘失败静默忽略（下次进服会重新同步）
        }
    }

    // 注：禁止在此类调用 finishCurrentTask——客户端的 ClientPayloadContext 会直接抛异常。
    // 服务端完成由 ServerPayloadHandler 用 ServerPayloadContext.finishCurrentTask(TYPE) 触发。
}
