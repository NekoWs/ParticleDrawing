package work.nekow.particledrawing.core.server

import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.util.HashUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 服务器端动画文件同步服务（纯逻辑，无网络依赖）。
 *
 * 服务器 `<gameDir>/animations/` 的 .pdrawc 为权威源；对比客户端上报的
 * SHA-1 清单，返回客户端缺失或内容变化的差异文件，供配置阶段增量下发。
 */
object AnimationSyncService {

    /** 需要同步的扩展名（相对 animations/ 根）；贴图内嵌于 .pdrawc，不单独同步 PNG。 */
    private val SYNC_EXTENSIONS = setOf(".pdrawc")

    /** 单个动画文件上限：与客户端读取上限一致，超大文件跳过不下发。 */
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** 待同步文件描述（相对文件名 + 文件字节）。 */
    data class SyncFile(val name: String, val bytes: ByteArray)

    /** 递归收集 animations/ 下所有待同步文件。 */
    fun collectServerFiles(): Map<String, ByteArray> {
        val root = AnimationLoader.DIRECTORY
        val result = LinkedHashMap<String, ByteArray>()
        forEachSyncFile(root) { rel, path ->
            try {
                if (Files.size(path) > MAX_FILE_BYTES) return@forEachSyncFile
                result[rel] = Files.readAllBytes(path)
            } catch (_: IOException) { /* 读取失败跳过 */ }
        }
        return result
    }

    /**
     * 增量差异：返回客户端缺失或内容变化（SHA-1 不同）的文件，按相对名稳定顺序。
     */
    fun computeDiff(clientHashes: Map<String, String>): List<SyncFile> {
        // 逐文件流式哈希，只有真正需要下发的文件才读进内存。
        val entries = ArrayList<Pair<String, Path>>()
        forEachSyncFile(AnimationLoader.DIRECTORY) { rel, path -> entries.add(rel to path) }
        entries.sortBy { it.first }
        val diff = ArrayList<SyncFile>()
        for ((name, path) in entries) {
            try {
                if (Files.size(path) > MAX_FILE_BYTES) continue
                val serverHash = hashFile(path) ?: continue
                if (clientHashes[name] == serverHash) continue
                diff.add(SyncFile(name, Files.readAllBytes(path)))
            } catch (_: IOException) {
                // 读取失败跳过，下次进服再同步
            }
        }
        return diff
    }

    /** 流式 SHA-1；读取失败返回 null。 */
    private fun hashFile(path: Path): String? = try {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** 校验相对文件名：允许 Unicode 字符，长度上限 512，禁止 .. 与控制字符。 */
    fun sanitizeRelativeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 512) return false
        if (name.contains("..")) return false
        // 禁止 ASCII 控制字符与文件系统非法字符：< > : " | ? *
        for (c in name) {
            if (c.code < 0x20) return false
            if (c in "<>:\"|?*") return false
        }
        return true
    }

    /** 计算本地动画文件（.pdrawc）相对 animations/ 的 SHA-1 清单。 */
    fun computeLocalHashes(root: Path): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        forEachSyncFile(root) { rel, path ->
            try {
                if (Files.size(path) > MAX_FILE_BYTES) return@forEachSyncFile
                hashFile(path)?.let { result[rel] = it }
            } catch (_: IOException) { /* 忽略 */ }
        }
        return result
    }

    /** 遍历 root 下待同步文件，action 收到相对名与绝对 Path；遍历失败返回空结果。 */
    private fun forEachSyncFile(root: Path, action: (String, Path) -> Unit) {
        if (!Files.isDirectory(root)) return
        try {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().let { n -> SYNC_EXTENSIONS.any { ext -> n.endsWith(ext) } } }
                    .forEach { path ->
                        action(root.relativize(path).toString().replace('\\', '/'), path)
                    }
            }
        } catch (_: IOException) {
            // 遍历失败返回空结果
        }
    }
}
