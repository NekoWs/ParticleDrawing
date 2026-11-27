package work.nekow.particledrawing.command.v2

import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import java.util.UUID

/**
 * 命名程序模板：维度内的内存对象，保存“参数 + 动作原文”。
 *
 * 模板只存命令语法（原文），展开时先把 `{参数}` 文本替换成实参，再交给同一套 `then` 解析器；
 * 因而参数可以出现在公式、文字、数字与点源名称里。和组、点集一样不写存档，关卡卸载与停服时丢弃。
 */
internal object ProgramTemplates {
    data class Template(val name: String, val parameters: LinkedHashMap<String, String>, val body: String)

    private const val MAX_TEMPLATES = 256

    private val dimensions = hashMapOf<UUID, LinkedHashMap<String, Template>>()

    fun names(dimensionId: UUID): List<String> = dimensions[dimensionId]?.keys?.toList() ?: emptyList()

    fun find(dimensionId: UUID, name: String): Template? = dimensions[dimensionId]?.get(name)

    fun get(dimensionId: UUID, name: String): Template =
        find(dimensionId, name)
            ?: throw LocalizedArgumentException(translation("error.program.not_found", name))

    fun checkCapacity(dimensionId: UUID, name: String) {
        val entries = dimensions[dimensionId].orEmpty()
        requireLocalized(name in entries || entries.size < MAX_TEMPLATES) {
            translation("error.program.limit", MAX_TEMPLATES)
        }
    }

    fun define(dimensionId: UUID, name: String, parameters: Map<String, String>, body: String): Template {
        requireLocalized(body.isNotBlank()) { translation("error.program.empty") }
        checkCapacity(dimensionId, name)
        val template = Template(name, LinkedHashMap(parameters), body)
        dimensions.getOrPut(dimensionId) { linkedMapOf() }[name] = template
        return template
    }

    fun remove(dimensionId: UUID, names: List<String>) {
        val entries = dimensions[dimensionId] ?: return
        entries.keys.removeAll(names.toSet())
        if (entries.isEmpty()) dimensions.remove(dimensionId)
    }

    fun discard(dimensionId: UUID) { dimensions.remove(dimensionId) }

    fun clear() { dimensions.clear() }
}
