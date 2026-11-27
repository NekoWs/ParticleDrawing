package work.nekow.particledrawing.command.help

import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.translation

/**
 * `/pdraw help` 的交互式聊天帮助。
 *
 * 目录 → 主题命令列表 → 单条命令页（逐参数说明）→ 独立示例页。全部显示在聊天框里，
 * 翻页、跳转与「点击填入」都走聊天组件的点击事件。
 */
internal object HelpBook {
    private const val PAGE_SIZE = 6

    /** 目录页的主题顺序；`examples` 是单独的示例页。 */
    private val topics = listOf("particle", "emitter", "animation", "camera", "texture", "light", "examples")

    fun ids(): List<String> = topics

    fun open(source: CommandSourceStack, page: Int): Int = index(source, page)

    fun pageCount(): Int = maxOf(1, (topics.size + PAGE_SIZE - 1) / PAGE_SIZE)

    fun index(source: CommandSourceStack, page: Int): Int {
        val pages = pageCount()
        val current = page.coerceIn(1, pages)
        val slice = topics.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE)
        val lines = ArrayList<Component>()
        lines += HelpChat.header(
            translation("command.help.ui.page_title", current, pages).component(),
        )
        lines += HelpChat.nav(
            listOf(
                if (current > 1) {
                    HelpChat.button(translation("command.help.ui.prev_page"), "/pdraw help page ${current - 1}")
                } else {
                    HelpChat.disabled(
                        translation("command.help.ui.prev_page").component(),
                        translation("command.help.ui.first_page").component(),
                    )
                },
                if (current < pages) {
                    HelpChat.button(translation("command.help.ui.next_page"), "/pdraw help page ${current + 1}")
                } else {
                    HelpChat.disabled(
                        translation("command.help.ui.next_page").component(),
                        translation("command.help.ui.last_page").component(),
                    )
                },
            ),
        )
        for (topic in slice) {
            lines += HelpChat.item(
                translation("command.help.$topic.title").component(),
                translation("command.help.$topic.summary").component(),
                "/pdraw help $topic",
                translation("command.help.$topic.summary").component(),
            )
        }
        lines += HelpChat.bottom()
        return HelpChat.message(source, lines)
    }

    fun topic(source: CommandSourceStack, id: String): Int {
        if (id == "examples") return examples(source)
        if (id !in topics) {
            HelpChat.plain(source, HelpChat.translated("command.help.topic_missing", id))
            return 0
        }
        val commands = HelpCommands.ofTopic(id)
        val lines = ArrayList<Component>()
        lines += HelpChat.header(translation("command.help.$id.title").component())
        lines += HelpChat.nav(
            buildList {
                add(HelpChat.button(translation("command.help.ui.index"), "/pdraw help"))
             },
        )
        for (command in commands) {
            lines += HelpChat.item(
                translation(command.titleKey).component(),
                translation(command.summaryKey).component(),
                "/pdraw help cmd ${command.id}",
                translation(command.summaryKey).component(),
            )
        }
        lines += HelpChat.bottom()
        return HelpChat.message(source, lines)
    }

    fun command(source: CommandSourceStack, id: String): Int {
        val command = HelpCommands.get(id)
        if (command == null) {
            HelpChat.plain(source, HelpChat.translated("command.help.command_missing", id))
            return 0
        }
        val siblings = HelpCommands.ofTopic(command.topic)
        val position = siblings.indexOfFirst { it.id == command.id }
        val lines = ArrayList<Component>()
        lines += HelpChat.header(
            translation(
                "command.help.ui.command_title",
                translation("command.help.${command.topic}.title").component(),
                translation(command.titleKey).component(),
            ).component(),
        )
        lines += HelpChat.nav(
            buildList {
                add(HelpChat.button(translation("command.help.ui.index"), "/pdraw help"))
                add(HelpChat.button(translation("command.help.ui.topic"), "/pdraw help ${command.topic}"))
                if (position > 0) {
                    add(HelpChat.button(translation("command.help.ui.prev_cmd"), "/pdraw help cmd ${siblings[position - 1].id}"))
                }
                if (position in 0 until siblings.size - 1) {
                    add(HelpChat.button(translation("command.help.ui.next_cmd"), "/pdraw help cmd ${siblings[position + 1].id}"))
                }
            },
        )
        val hovers = command.params
            .flatMap { param -> param.name.split(' ').map { it to translation(param.effectKey).component() } }
            .toMap()
        lines += HelpChat.section("command.help.ui.usage")
        lines += HelpChat.commandLine(command.usage, hovers = hovers)
        lines += HelpChat.translated(command.summaryKey)
        if (command.params.isNotEmpty()) {
            lines += HelpChat.section("command.help.ui.params")
            for (param in command.params) {
                lines += HelpChat.param(param.name, translation(param.effectKey).component())
            }
        }
        lines += HelpChat.bottom()
        return HelpChat.message(source, lines)
    }

    fun examples(source: CommandSourceStack): Int {
        val lines = ArrayList<Component>()
        lines += HelpChat.header(translation("command.help.examples.title").component())
        lines += HelpChat.nav(
            listOf(
                HelpChat.button(translation("command.help.ui.index"), "/pdraw help"),
                HelpChat.button(translation("command.help.ui.commands"), "/pdraw help"),
            ),
        )
        lines += HelpChat.translated("command.help.examples.body")
        for (group in HelpCommands.exampleGroups) {
            lines += HelpChat.section(group.titleKey)
            for (example in group.entries) {
                lines += HelpChat.text("  - ", color = ChatFormatting.GRAY)
                    .append(HelpChat.translated(example.noteKey))
                lines += HelpChat.commandLine(example.command, indent = "    ")
            }
        }
        lines += HelpChat.bottom()
        return HelpChat.message(source, lines)
    }
}
