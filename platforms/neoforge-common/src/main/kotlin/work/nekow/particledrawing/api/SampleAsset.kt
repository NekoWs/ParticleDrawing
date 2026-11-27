package work.nekow.particledrawing.api

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * 采样素材：工作目录下的相对文件，或资源包里的标识。
 *
 * 文字之外的图片/模型采样共用这里；[open] 限制相对路径在工作目录内，并拒绝符号链接逃逸。
 */
data class SampleAsset(val path: String, val resource: Boolean) {

    fun open(level: ServerLevel, directory: String, missingKey: String): InputStream {
        if (resource) {
            return level.server.resourceManager.getResource(Identifier.parse(path))
                .orElseThrow { LocalizedArgumentException(translation(missingKey, path)) }.open()
        }
        val root = Path.of(directory)
        val target = safePath(root, path)
        requireLocalized(Files.isRegularFile(target)) { translation(missingKey, target) }
        return Files.newInputStream(target)
    }

    companion object {
        /** 相对路径解析：拒绝绝对路径、越界与符号链接逃逸。 */
        internal fun safePath(root: Path, name: String): Path {
            requireLocalized(!Path.of(name).isAbsolute) { translation("error.asset.relative_path", root.fileName) }
            val base = root.toAbsolutePath().normalize()
            val target = base.resolve(name).normalize()
            requireLocalized(target.startsWith(base) && target != base) {
                translation("error.asset.outside_directory", root.fileName)
            }
            if (Files.exists(target)) {
                requireLocalized(target.toRealPath().startsWith(base.toRealPath())) {
                    translation("error.asset.outside_directory", root.fileName)
                }
            }
            return target
        }
    }
}
