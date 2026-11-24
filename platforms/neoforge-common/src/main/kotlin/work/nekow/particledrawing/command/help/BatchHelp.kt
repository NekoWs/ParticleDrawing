package work.nekow.particledrawing.command.help

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import work.nekow.particledrawing.command.batch.BatchKey
import work.nekow.particledrawing.command.batch.BatchKeys
import work.nekow.particledrawing.command.batch.BatchKind
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.translation
import java.util.concurrent.CompletableFuture

/**
 * `/pdraw particle batch help` 的交互式键目录。
 *
 * 总览页给 kind 与分区的入口；列表页按 8 个键分页，点键名进详情；详情页给出适用 kind、值语法、
 * 枚举值、分区介绍和一个可点击填入的模板命令。翻页与跳转都靠聊天组件的点击事件发命令。
 */
internal object BatchHelp {
    private const val PAGE_SIZE = 8

    private val groupIds = linkedMapOf(
        "顶层" to "top",
        "锚点" to "anchor",
        "采样" to "sampling",
        "公式" to "formula",
        "运行期数值" to "runtime",
        "发射器" to "emitter",
        "碰撞" to "collision",
        "子发射" to "subemit",
        "清除" to "unset",
        "形状" to "shape",
        "一次性粒子" to "particle",
        "文字" to "text",
        "组编排" to "program",
        "形变" to "morph",
        "外观" to "appearance",
    )

    fun overview(source: CommandSourceStack): Int {
        val lines = ArrayList<Component>()
        lines += title()
        lines += HelpChat.translated("command.batch.help.overview")
        lines += section("command.batch.help.usage")
        lines += suggest("/pdraw particle batch <名字> kind=emitter <键>=<值>...")
        lines += suggest("/pdraw particle batch <名字> kind=<形状> <键>=<值>...")
        lines += suggest("/pdraw particle batch <名字> kind=program <键>=<值>...")
        lines += HelpChat.lines(listOf(
            HelpChat.text(translation("command.batch.help.kinds_label").resolve() + "：", color = ChatFormatting.YELLOW),
            buttonsRow(
                BatchKind.entries.map { kind ->
                    HelpButton(
                        translation("command.batch.help.kind.${kindLabel(kind)}"),
                        "/pdraw particle batch help kind ${kindLabel(kind)}",
                    )
                } + HelpButton(
                    translation("command.batch.help.all"),
                    "/pdraw particle batch help page 1",
                    color = ChatFormatting.GOLD,
                ),
            ),
        ))
        lines += HelpChat.text(translation("command.batch.help.groups_label").resolve() + "：", color = ChatFormatting.YELLOW)
        val groups = BatchKeys.groups
        for (row in groups.chunked(4)) {
            lines += buttonsRow(row.map { group -> groupButton(group) })
        }
        lines += footer(listOf(
            HelpButton(translation("command.help.ui.index"), "/pdraw help"),
            HelpButton(translation("command.batch.help.all"), "/pdraw particle batch help page 1", color = ChatFormatting.GOLD),
        ))
        return HelpChat.message(source, lines)
    }

    fun list(source: CommandSourceStack, kind: BatchKind?, group: String?, page: Int): Int {
        val keys = when {
            group != null -> BatchKeys.all.filter { it.group.equals(group, ignoreCase = true) }
            kind != null -> BatchKeys.keysFor(kind)
            else -> BatchKeys.all
        }
        if (keys.isEmpty()) {
            HelpChat.plain(source, HelpChat.translated("command.batch.help.empty", group ?: kindLabel(kind)))
            return 0
        }
        val pages = (keys.size + PAGE_SIZE - 1) / PAGE_SIZE
        val current = page.coerceIn(1, pages)
        val slice = keys.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE)
        val lines = ArrayList<Component>()
        val titleKey = when {
            group != null -> "command.batch.help.title_group"
            kind != null -> "command.batch.help.title_kind"
            else -> "command.batch.help.title_all"
        }
        lines += HelpChat.text(
            translation(titleKey, group ?: kindLabel(kind).ifEmpty { "all" }, current, pages).resolve(),
            color = ChatFormatting.GOLD,
            bold = true,
        )
        lines += HelpChat.text(translation("command.batch.help.list_hint").resolve(), color = ChatFormatting.DARK_GRAY)
        for (entry in slice) {
            val row = HelpChat.text(
                entry.name,
                color = ChatFormatting.AQUA,
                clickKind = HelpClick.RUN,
                command = "/pdraw particle batch help key ${entry.name}",
                hover = hoverOf(entry),
            )
            row.append(HelpChat.text(" = " + entry.syntax, color = ChatFormatting.GRAY))
            lines += row
        }
        val nav = ArrayList<HelpButton>()
        if (current > 1) nav += HelpButton(translation("command.help.ui.prev"), pageCommand(kind, group, current - 1))
        if (current < pages) nav += HelpButton(translation("command.help.ui.next"), pageCommand(kind, group, current + 1))
        nav += HelpButton(translation("command.batch.help.back"), "/pdraw particle batch help")
        lines += footer(nav)
        return HelpChat.message(source, lines)
    }

    fun detail(source: CommandSourceStack, name: String): Int {
        val entry = BatchKeys.exact(name) ?: return unknown(source, name)
        val lines = ArrayList<Component>()
        lines += HelpChat.text("key: " + entry.name, color = ChatFormatting.GOLD, bold = true)
        lines += info("command.batch.help.applies", entry.kinds.joinToString(" / ") { kindLabel(it) })
        lines += info("command.batch.help.group_label", entry.group)
        lines += info("command.batch.help.syntax", entry.syntax)
        if (entry.repeatable) lines += infoText("command.batch.help.repeatable", translation("command.batch.help.yes"))
        entry.choices?.let { lines += infoText("command.batch.help.choices", translation("command.batch.help.choices_format", it.joinToString(" | "))) }
        lines += section("command.batch.help.intro")
        lines += HelpChat.text(translation("command.batch.group.${groupKey(entry.group)}").resolve(), color = ChatFormatting.WHITE)
        val template = template(entry)
        lines += section("command.batch.help.template")
        lines += HelpChat.text(
            template,
            color = ChatFormatting.GREEN,
            clickKind = HelpClick.SUGGEST,
            command = template,
            hover = translation("command.help.ui.click_fill").component(),
        )
        val nav = ArrayList<HelpButton>()
        val all = BatchKeys.all
        val index = all.indexOfFirst { it.name == entry.name }
        if (index > 0) nav += HelpButton(translation("command.batch.help.prev_key"), "/pdraw particle batch help key ${all[index - 1].name}")
        if (index >= 0 && index < all.size - 1) {
            nav += HelpButton(translation("command.batch.help.next_key"), "/pdraw particle batch help key ${all[index + 1].name}")
        }
        nav += HelpButton(translation("command.batch.help.back"), "/pdraw particle batch help page 1")
        lines += footer(nav)
        return HelpChat.message(source, lines)
    }

    fun handle(source: CommandSourceStack, raw: String): Int {
        val parts = raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (parts.isEmpty()) return overview(source)
        parts[0].toIntOrNull()?.let { return list(source, null, null, it) }
        when (parts[0].lowercase()) {
            "page" -> return list(source, null, null, parts.getOrNull(1)?.toIntOrNull() ?: 1)
            "kind" -> {
                val rawKind = parts.getOrNull(1) ?: return overview(source)
                val kind = BatchKind.of(rawKind) ?: return unknown(source, rawKind)
                return list(source, kind, null, parts.getOrNull(2)?.toIntOrNull() ?: 1)
            }
            "group" -> {
                var rest = parts.drop(1)
                val page = rest.lastOrNull()?.toIntOrNull()
                if (page != null) rest = rest.dropLast(1)
                val group = rest.joinToString(" ")
                return list(source, null, group, page ?: 1)
            }
            "key" -> return detail(source, parts.drop(1).joinToString(" "))
        }
        val joined = parts.joinToString(" ")
        BatchKeys.exact(joined)?.let { return detail(source, joined) }
        BatchKeys.groups.firstOrNull { it.equals(joined, ignoreCase = true) }?.let {
            return list(source, null, it, 1)
        }
        return unknown(source, joined)
    }

    fun suggest(
        ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val raw = try {
            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "args")
        } catch (_: IllegalArgumentException) {
            ""
        }
        val prefix = raw.substringAfterLast(' ')
        val before = raw.substring(0, raw.length - prefix.length)
        val options = linkedSetOf<String>()
        when {
            before.isBlank() -> {
                options += "group"
                options += "kind"
                options += "key"
                options += "page"
                options += BatchKeys.groups
            }
            before.trim() == "group" -> options += BatchKeys.groups
            before.trim() == "kind" -> options += BatchKind.entries.map(::kindLabel)
            before.trim() == "key" -> options += BatchKeys.all.map { it.name }
            else -> Unit
        }
        return SharedSuggestionProvider.suggest(
            options.filter { it.startsWith(prefix, ignoreCase = true) },
            builder.createOffset(builder.input.lastIndexOf(' ') + 1),
        )
    }

    private fun pageCommand(kind: BatchKind?, group: String?, page: Int): String = when {
        group != null -> "/pdraw particle batch help group $group $page"
        kind != null -> "/pdraw particle batch help kind ${kindLabel(kind)} $page"
        else -> "/pdraw particle batch help page $page"
    }

    private fun kindLabel(kind: BatchKind?): String = when (kind) {
        BatchKind.EMITTER -> "emitter"
        BatchKind.SHAPE -> "shape"
        BatchKind.PROGRAM -> "program"
        null -> "all"
    }

    private fun groupKey(group: String): String =
        groupIds[group] ?: group.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifEmpty { "misc" }

    private fun title(): Component = HelpChat.text(
        translation("command.batch.help.title").resolve(),
        color = ChatFormatting.GOLD,
        bold = true,
    )

    private fun section(key: String): Component =
        HelpChat.text(translation(key).resolve() + "：", color = ChatFormatting.YELLOW)

    private fun suggest(command: String): Component = HelpChat.text(
        command,
        color = ChatFormatting.GREEN,
        clickKind = HelpClick.SUGGEST,
        command = command,
        hover = translation("command.help.ui.click_fill").component(),
    )

    private fun info(key: String, value: String): Component = HelpChat.text(
        translation(key).resolve() + "：" + value,
        color = ChatFormatting.GRAY,
    )

    private fun infoText(key: String, value: work.nekow.particledrawing.i18n.LocalizedText): Component = HelpChat.text(
        translation(key).resolve() + "：" + value.resolve(),
        color = ChatFormatting.GRAY,
    )

    private fun footer(buttons: List<HelpButton>): Component = buttonsRow(buttons)

    private fun groupButton(group: String): HelpButton = HelpButton(
        translation("command.batch.group.button", group),
        "/pdraw particle batch help group $group",
        hover = translation("command.batch.group.${groupKey(group)}"),
    )

    private fun hoverOf(entry: BatchKey): Component = HelpChat.lines(listOf(
        HelpChat.text(entry.name, color = ChatFormatting.WHITE),
        HelpChat.text(
            translation("command.batch.help.hover", entry.kinds.joinToString(" / ") { kindLabel(it) }, entry.group).resolve(),
            color = ChatFormatting.GRAY,
        ),
        HelpChat.text(entry.syntax, color = ChatFormatting.DARK_GRAY),
    ))

    private fun unknown(source: CommandSourceStack, value: String): Int {
        HelpChat.plain(source, HelpChat.translated("command.batch.help.unknown", value))
        return 0
    }

    private fun template(entry: BatchKey): String {
        val kind = kindLabel(entry.kinds.firstOrNull())
        val name = entry.name
            .replace("<名字>", "ang")
            .replace("<属性>", "x")
            .replace("<序号>", "1")
            .replace("<字段>", "采样")
        return "/pdraw particle batch example kind=$kind $name=${sampleValue(entry)}"
    }

    private fun sampleValue(entry: BatchKey): String {
        entry.choices?.firstOrNull()?.let { return it }
        val key = entry.name.substringAfterLast('.')
        if (entry.name.startsWith("appearance.")) {
            return when (key) {
                "color" -> "#ff8844"
                "style" -> "soft_dot"
                "texture" -> "particle:minecraft:end_rod"
                "size_world" -> "0.2,0.2"
                "size" -> "0.2,0.2"
                "additive", "glowing", "billboard", "light_only", "align_velocity" -> "true"
                "light" -> "8"
                "trail" -> "16"
                "spin" -> "0,0,0"
                "scale" -> "1"
                else -> "值"
            }
        }
        return when (val syntax = entry.syntax) {
            "x,y,z" -> "0,0,0"
            "x1,y1,z1,x2,y2,z2" -> "0,0,0,4,4,4"
            "begin,end,step" -> "-10,10,0.1"
            "动作,参数..." -> "delay,20"
            "数量,双引号位置公式" -> "1024,\"vec3(u*8,0,0)\""
            "文字，| 换行" -> "HELLO"
            "字形高度" -> "2"
            "文件名" -> "example.png"
            "选择器" -> "@p"
            "配置名" -> "root"
            "段名或段.字段" -> "采样"
            "形变缓动名" -> "in_out_cubic"
            "形变动作" -> "morph.start"
            "峰值,半周期[,次数]" -> "1.2,20,-1"
            "-1 或刻数" -> "200"
            "0..72000" -> "0"
            else -> when {
                syntax.startsWith("双引号") -> "\"1+0\""
                syntax.startsWith("1..") || syntax.startsWith("0..") -> "64"
                syntax.startsWith("0.05..") -> "1"
                syntax == "小数" -> "1"
                syntax == "整数" -> "1"
                else -> "值"
            }
        }
    }
}
