package work.nekow.particledrawing.animation

import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * 从磁盘读取 .pdrawc 二进制播放文件（网页编辑器「导出动画」产物）。
 */
object AnimationLoader {

    /** 动画播放文件存放目录：`<gameDir>/animations/`。 */
    val DIRECTORY: Path = FMLPaths.GAMEDIR.get().resolve("animations")

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

    /** 动画文件路径（按名称，仅防路径穿越，不剥离 Unicode）。 */
    private fun resolvePath(name: String): Path {
        val safe = name.replace(Regex("[/\\\\]"), "_")
        return DIRECTORY.resolve("$safe.pdrawc")
    }

    /** 判断某名称对应的 .pdrawc 文件是否存在（不验签）。 */
    @JvmStatic
    fun has(name: String): Boolean = Files.exists(resolvePath(name))

    /**
     * 按名称读取动画播放文件字节并做服务端验签；缺失或验签失败返回 null。
     */
    @JvmStatic
    fun load(name: String): ByteArray? {
        val path = resolvePath(name)
        if (!Files.exists(path)) return null
        val bytes = Files.readAllBytes(path)
        return if (PdrawcReader.verify(bytes)) bytes else null
    }

    /**
     * 解析 .pdrawc 字节为 [ParticleAnimation]（客户端解析时再次验签）。
     * 验签失败或格式损坏抛异常，调用方据此拒绝播放。
     */
    @JvmStatic
    fun parse(bytes: ByteArray): ParticleAnimation = PdrawcReader.parse(bytes)
}