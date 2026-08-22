package work.nekow.particledrawing.core.server

import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.util.HashUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 服务器端动画文件同步服务（纯逻辑，无网络依赖）。
 *
 * 服务器 `<gameDir>/animations/`（.pdraw 与 textures 下的 .png）为权威源；对比客户端上报的
 * SHA-1 清单，返回客户端缺失或内容变化的差异文件，供配置阶段增量下发。
 */
object AnimationSyncService {

    /** 需要同步的扩展名（相对 animations/ 根）。贴图已内嵌于 pdraw v4+，不再单独同步 PNG。 */
    private val SYNC_EXTENSIONS = setOf(".pdraw")

    /** 待同步文件描述（相对文件名 + 文件字节）。 */
    data class SyncFile(val name: String, val bytes: ByteArray)

    /** 递归收集 animations/ 下所有待同步文件。 */
    fun collectServerFiles(): Map<String, ByteArray> {
        val root = AnimationLoader.DIRECTORY
        if (!Files.isDirectory(root)) return emptyMap()
        val result = LinkedHashMap<String, ByteArray>()
        try {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().let { n -> SYNC_EXTENSIONS.any { ext -> n.endsWith(ext) } } }
                    .forEach { path ->
                        val rel = root.relativize(path).toString().replace('\\', '/')
                        try {
                            result[rel] = Files.readAllBytes(path)
                        } catch (_: IOException) {
                            // 读取失败跳过
                        }
                    }
            }
        } catch (_: IOException) {
            return emptyMap()
        }
        return result
    }

    /**
     * 增量差异：返回客户端缺失或内容变化（SHA-1 不同）的文件，按相对名稳定顺序。
     */
    fun computeDiff(clientHashes: Map<String, String>): List<SyncFile> {
        val serverFiles = collectServerFiles()
        val diff = ArrayList<SyncFile>()
        val names = serverFiles.keys.sorted()
        for (name in names) {
            val bytes = serverFiles[name] ?: continue
            val serverHash = HashUtils.sha1Hex(bytes)
            if (clientHashes[name] == serverHash) continue
            diff.add(SyncFile(name, bytes))
        }
        return diff
    }

    /** 校验相对文件名，防止路径穿越（允许 Unicode 字符，禁止 .. 和控制字符）。 */
    fun sanitizeRelativeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 512) return false
        if (name.contains("..")) return false
        // 禁止 ASCII 控制字符和文件系统非法字符（Windows: < > : " | ? *）
        for (c in name) {
            if (c.code < 0x20) return false
            if (c in "<>:\"|?*") return false
        }
        return true
    }

    /** 计算本地动画文件（.pdraw 与 textures 下的 .png）相对 animations/ 的 SHA-1 清单。 */
    fun computeLocalHashes(root: Path): Map<String, String> {
        if (!Files.isDirectory(root)) return emptyMap()
        val result = LinkedHashMap<String, String>()
        try {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().let { n -> SYNC_EXTENSIONS.any { ext -> n.endsWith(ext) } } }
                    .forEach { path ->
                        if (path.toFile().length() > 64L * 1024 * 1024) return@forEach
                        val rel = root.relativize(path).toString().replace('\\', '/')
                        try {
                            result[rel] = HashUtils.sha1Hex(Files.readAllBytes(path))
                        } catch (_: IOException) {
                            // 忽略
                        }
                    }
            }
        } catch (_: IOException) {
            return emptyMap()
        }
        return result
    }
}
