package work.nekow.particledrawing.core.client

import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.PdrawcReader
import work.nekow.particledrawing.core.network.AnimationSyncRequestPayload
import work.nekow.particledrawing.core.server.AnimationSyncService
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 客户端动画文件同步管理器（配置阶段，按服务器区分目录）。
 *
 * 状态机：
 * 1. 服务器配置任务发来「开始」信号 → 识别连接：
 *    - 内存连接（单机 / LAN 主机）→ 回空清单，跳过文件同步；
 *    - 远程连接 → 推导 `animations/servers/<serverKey>` 缓存根目录，
 *      上报该目录内已有文件哈希。
 * 2. 逐块接收 [AnimationSyncFilePayload]，按文件名累积；
 * 3. 收到 eof 块 → 验签后写入当前服务器缓存根目录；
 * 4. 收到 [AnimationSyncDonePayload] → 清理会话缓存，服务端结束配置任务
 *    （客户端不再调用 finishCurrentTask）。
 */
object ClientAnimationSyncManager {

    /** 正在累积的文件内容（相对名 → 字节流）。 */
    private val pendingFiles = LinkedHashMap<String, ByteArrayOutputStream>()

    /** 本次连接同步到的服务器缓存根目录；内存连接或尚未开始时为 null。 */
    private var currentRoot: Path? = null

    /** 会话开始：识别连接并上报该服务器目录下已有文件哈希。 */
    @JvmStatic
    fun onBegin(context: IPayloadContext) {
        pendingFiles.clear()
        currentRoot = null
        if (context.connection().isMemoryConnection()) {
            // 单机 / LAN 主机：服务器与客户端共享同一目录，无需同步文件
            context.reply(AnimationSyncRequestPayload(emptyMap()))
            return
        }
        val key = serverKey(context)
        if (key == null) {
            context.reply(AnimationSyncRequestPayload(emptyMap()))
            return
        }
        val root = AnimationLoader.DIRECTORY.resolve("servers").resolve(key).normalize()
        if (!root.startsWith(AnimationLoader.DIRECTORY)) {
            context.reply(AnimationSyncRequestPayload(emptyMap()))
            return
        }
        currentRoot = root
        val hashes = AnimationSyncService.computeLocalHashes(root)
        context.reply(AnimationSyncRequestPayload(hashes))
    }

    /** 接收一个文件块。 */
    @JvmStatic
    fun onFileChunk(name: String, eof: Boolean, data: ByteArray) {
        val root = currentRoot ?: return
        // 累积前先校验相对名，避免把非法路径/异常名缓存在 pendingFiles 里
        if (!AnimationSyncService.sanitizeRelativeName(name.replace('\\', '/'))) return
        val out = pendingFiles.getOrPut(name) { ByteArrayOutputStream() }
        out.write(data)
        if (eof) {
            val bytes = out.toByteArray()
            // .pdrawc 落盘前验签，验签失败的文件不写入（下次进服会重新同步）
            if (!name.endsWith(".pdrawc") || PdrawcReader.verify(bytes)) {
                writeFile(root, name, bytes)
            }
            pendingFiles.remove(name)
        }
    }

    /** 同步完成：清理缓存，等待服务端结束配置任务（客户端禁止调用 finishCurrentTask）。 */
    @JvmStatic
    fun onDone(context: IPayloadContext) {
        pendingFiles.clear()
        currentRoot = null
    }

    /** 从连接远端地址推导服务器目录名：可读 host，非默认端口追加 `_port`。 */
    private fun serverKey(context: IPayloadContext): String? {
        val addr = context.connection().remoteAddress as? InetSocketAddress ?: return null
        val host = addr.hostString ?: addr.address?.hostAddress ?: return null
        val safe = buildString {
            for (c in host.take(128)) {
                append(if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_') c else '_')
            }
        }.trimEnd('.')
        val normalized = safe.ifEmpty { "server" }
        return if (addr.port == 25565) normalized else "${normalized}_${addr.port}"
    }

    /** 把单个同步文件写入当前服务器缓存目录（覆盖；先建父目录）。 */
    private fun writeFile(root: Path, name: String, bytes: ByteArray) {
        val rel = name.replace('\\', '/')
        if (!AnimationSyncService.sanitizeRelativeName(rel)) return
        val target: Path = root.resolve(rel).normalize()
        if (!target.startsWith(root)) return
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