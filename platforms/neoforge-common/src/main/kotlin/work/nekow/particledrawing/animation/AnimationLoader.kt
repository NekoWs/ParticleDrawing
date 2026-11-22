package work.nekow.particledrawing.animation

import net.neoforged.fml.loading.FMLPaths
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.translation
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 从磁盘读取 .pdrawc 二进制播放文件（网页编辑器导出产物）。
 */
object AnimationLoader {

    /** 动画播放文件存放目录：`<gameDir>/animations/`。 */
    val DIRECTORY: Path = FMLPaths.GAMEDIR.get().resolve("animations")

    /** 单个动画文件上限：再大的一律拒绝，避免读到超大文件把内存撑爆。 */
    private const val MAX_FILE_BYTES = 64L shl 20

    /** 列出可用的动画名（不含 .pdrawc 后缀）。 */
    @JvmStatic
    fun list(): List<String> {
        if (!Files.isDirectory(DIRECTORY)) return emptyList()
        return Files.list(DIRECTORY).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".pdrawc") }
                .map { it.fileName.toString().removeSuffix(".pdrawc") }
                .sorted()
                .toList()
        }
    }

    /** 某名称对应的文件路径；分隔符替换为下划线以防路径穿越，不剥离 Unicode；非法名返回 null。 */
    private fun resolvePath(name: String): Path? {
        val safe = name.replace(Regex("[/\\\\]"), "_")
        return try {
            DIRECTORY.resolve("$safe.pdrawc")
        } catch (_: InvalidPathException) {
            null
        }
    }

    /** 判断某名称对应的 .pdrawc 文件是否存在（不验签）。 */
    @JvmStatic
    fun has(name: String): Boolean = resolvePath(name)?.let { Files.exists(it) } ?: false

    /** 按名称读取动画播放文件字节并做服务端验签；缺失或验签失败返回 null。 */
    @JvmStatic
    fun load(name: String): ByteArray? {
        val path = resolvePath(name) ?: return null
        if (!Files.exists(path)) return null
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw LocalizedArgumentException(translation("error.file.too_large", MAX_FILE_BYTES))
        }
        val bytes = Files.readAllBytes(path)
        return if (PdrawcReader.verify(bytes)) bytes else null
    }

    /**
     * 解析 .pdrawc 字节为 [ParticleAnimation]（再次验签）。
     * 验签失败或格式损坏抛异常。
     */
    @JvmStatic
    fun parse(bytes: ByteArray): ParticleAnimation = PdrawcReader.parse(bytes)
}