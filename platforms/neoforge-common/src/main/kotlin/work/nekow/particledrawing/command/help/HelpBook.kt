package work.nekow.particledrawing.command.help

import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.translation

/**
 * `/pdraw help` 的交互式帮助目录。
 *
 * 全部内容显示在聊天框里：每页一条多行消息 + 一行按钮，按钮用聊天组件的点击事件翻页、跳转或把示例命令填进输入框。
 * 主题正文放在语言文件里，命令用法和示例是纯命令文本，方便直接点进聊天框。
 */
internal data class HelpTopic(
    val id: String,
    val usage: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val related: List<String> = emptyList(),
)

internal object HelpBook {
    private const val PAGE_SIZE = 6

    private val topics = listOf(
        HelpTopic(
            "overview",
            usage = listOf(
                "/pdraw particle emitter <名称> ...",
                "/pdraw particle batch <名字> kind=... <键>=<值>...",
                "/pdraw particle group create|reset <名字> <x> <y> <z>",
                "/pdraw animation list|play|stop|camera|var",
                "/pdraw camera pos|look|stop",
            ),
            examples = listOf(
                "pdraw particle batch ring kind=emitter anchor=fixed anchor.pos=~ ~4 ~ sample.count=64 " +
                    "appearance.style=soft_dot appearance.color=#b060ff mode=start",
            ),
            related = listOf("particle", "batch", "examples"),
        ),
        HelpTopic(
            "particle",
            usage = listOf(
                "/pdraw particle add <组> <形状> <位置> ...",
                "/pdraw particle group create|reset|list|remove|clear",
                "/pdraw particle program <组> <动作> ...",
                "/pdraw particle emitter <名称> create|reset <名称>",
            ),
            examples = listOf(
                "pdraw particle add demo circle ~ ~1 ~ 3 96 color=#55ddff style=soft_dot additive glowing",
            ),
            related = listOf("emitter", "batch"),
        ),
        HelpTopic(
            "emitter",
            usage = listOf(
                "/pdraw particle emitter <名称> sample count|range|grid|image|mesh|text ...",
                "/pdraw particle emitter <名称> birth x|y|z|position|velocity|r|g|b ...",
                "/pdraw particle emitter <名称> motion vx|vy|vz|velocity|acceleration ...",
                "/pdraw particle emitter <名称> lifetime|rate|max_alive|emit_from|emit_until ...",
                "/pdraw particle emitter <名称> anchor fixed|entity|emitter ...",
                "/pdraw particle emitter <名称> appearance <键> <值> ...",
                "/pdraw particle emitter <名称> burst|start|stop|remove",
            ),
            examples = listOf(
                "pdraw particle emitter meteor create",
                "pdraw particle emitter meteor anchor fixed ~ ~4 ~",
                "pdraw particle emitter meteor sample count 200",
                "pdraw particle emitter meteor birth position vec3((rand()-0.5)*8,0,(rand()-0.5)*8)",
                "pdraw particle emitter meteor motion vy -0.05",
                "pdraw particle emitter meteor appearance style soft_dot",
                "pdraw particle emitter meteor burst",
            ),
            related = listOf("batch", "particle"),
        ),
        HelpTopic(
            "batch",
            usage = listOf(
                "/pdraw particle batch <名字> kind=emitter <键>=<值>...",
                "/pdraw particle batch <名字> kind=<形状> <键>=<值>...",
                "/pdraw particle batch <名字> kind=program <键>=<值>...",
                "/pdraw particle batch help [页码|group 名|kind emitter|key 键名]",
            ),
            examples = listOf(
                "pdraw particle batch hello kind=text center=~ ~2 ~ text=HELLO count=512 " +
                    "appearance.style=soft_dot appearance.color=#ffd166",
                "pdraw particle batch ring kind=circle center=~ ~4 ~ radius=4 count=200 " +
                    "appearance.style=soft_dot appearance.additive=true appearance.glowing=true " +
                    "appearance.color=#7fe0ff particle.lifetime=200",
            ),
            related = listOf("emitter", "particle", "examples"),
        ),
        HelpTopic(
            "animation",
            usage = listOf(
                "/pdraw animation list",
                "/pdraw animation play <名字> [位置]",
                "/pdraw animation stop",
                "/pdraw animation camera [摄像机] [stop]",
                "/pdraw animation var <名字> <JSON>",
                "/pdraw animation debug",
            ),
            examples = listOf("/pdraw animation list"),
            related = listOf("camera"),
        ),
        HelpTopic(
            "camera",
            usage = listOf(
                "/pdraw camera pos <x> <y> <z> <yaw> <pitch> [fov] [smooth]",
                "/pdraw camera look <x> <y> <z> <目标 x> <目标 y> <目标 z> [fov] [smooth]",
                "/pdraw camera stop",
            ),
            examples = listOf("/pdraw camera look 0 100 0 0 100 24 70 12"),
            related = listOf("animation"),
        ),
        HelpTopic(
            "texture",
            usage = listOf(
                "/pdraw texture reload",
                "texture=particle:<命名空间>:<粒子名>",
                "texture=particle:<命名空间>:<粒子名>#<帧号>",
                "texture=particle:<命名空间>:<粒子名>@<帧率>",
            ),
            examples = listOf("/pdraw texture reload"),
            related = listOf("particle"),
        ),
        HelpTopic(
            "light",
            usage = listOf("/pdraw light"),
            examples = listOf("/pdraw light"),
            related = listOf("particle"),
        ),
        HelpTopic(
            "examples",
            usage = listOf(
                "examples/commands/*.mcfunction",
                "examples/showcase/datapack（星海神殿五个效果）",
                "doc/particle-commands.md",
                "doc/batch-command.md",
            ),
            related = listOf("batch"),
        ),
    )

    private val byId = topics.associateBy(HelpTopic::id)

    fun ids(): List<String> = topics.map(HelpTopic::id)

    fun open(source: CommandSourceStack, page: Int): Int = index(source, page)

    fun topic(source: CommandSourceStack, id: String): Int {
        val topic = byId[id.lowercase()]
        if (topic == null) {
            HelpChat.plain(source, HelpChat.translated("command.help.topic_missing", id))
            return 0
        }
        val lines = ArrayList<Component>()
        lines += title("command.help.${topic.id}.title", "「", "」")
        lines += HelpChat.translated("command.help.${topic.id}.body")
        if (topic.usage.isNotEmpty()) {
            lines += section("command.help.ui.usage")
            topic.usage.forEach { usage -> lines += clickable(usage, ChatFormatting.GRAY) }
        }
        if (topic.examples.isNotEmpty()) {
            lines += section("command.help.ui.example")
            topic.examples.forEach { example -> lines += clickable(example, ChatFormatting.GREEN) }
        }
        if (topic.related.isNotEmpty()) {
            lines += section("command.help.ui.related")
            lines += buttonsRow(topic.related.map { related ->
                HelpButton(
                    translation("command.help.${related}.title"),
                    "/pdraw help $related",
                    hover = translation("command.help.${related}.summary"),
                )
            })
        }
        lines += footer(listOf(
            HelpButton(translation("command.help.ui.index"), "/pdraw help"),
            HelpButton(translation("command.help.ui.catalog"), "/pdraw particle batch help", color = ChatFormatting.GOLD),
        ))
        return HelpChat.message(source, lines)
    }

    fun index(source: CommandSourceStack, page: Int): Int {
        val listed = topics.filter { it.id != "overview" }
        val pages = maxOf(1, (listed.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val current = page.coerceIn(1, pages)
        val slice = listed.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE)
        val lines = ArrayList<Component>()
        lines += HelpChat.text("ParticleDrawing", color = ChatFormatting.GOLD, bold = true)
            .append(HelpChat.text(" · " + translation("command.help.ui.index").resolve(), color = ChatFormatting.GRAY))
        lines += HelpChat.translated("command.help.overview.body")
        for (topic in slice) {
            val row = HelpChat.text(
                "[" + translation("command.help.${topic.id}.title").resolve() + "]",
                color = ChatFormatting.AQUA,
                clickKind = HelpClick.RUN,
                command = "/pdraw help ${topic.id}",
                hover = translation("command.help.${topic.id}.summary").component(),
            )
            row.append(HelpChat.text(" " + translation("command.help.${topic.id}.summary").resolve(), color = ChatFormatting.DARK_GRAY))
            lines += row
        }
        lines += footer(
            buildList {
                if (current > 1) add(HelpButton(translation("command.help.ui.prev"), "/pdraw help page ${current - 1}"))
                if (current < pages) add(HelpButton(translation("command.help.ui.next"), "/pdraw help page ${current + 1}"))
                add(HelpButton(translation("command.help.ui.catalog"), "/pdraw particle batch help", color = ChatFormatting.GOLD))
            },
        )
        return HelpChat.message(source, lines)
    }

    private fun title(key: String, prefix: String, suffix: String): Component {
        val text = translation(key).resolve()
        return HelpChat.text(prefix, color = ChatFormatting.DARK_GRAY)
            .append(HelpChat.text(text, color = ChatFormatting.GOLD, bold = true))
            .append(HelpChat.text(suffix, color = ChatFormatting.DARK_GRAY))
    }

    private fun section(key: String): Component =
        HelpChat.text(translation(key).resolve() + "：", color = ChatFormatting.YELLOW)

    private fun clickable(command: String, color: ChatFormatting): Component {
        val isCommand = command.startsWith("/") || command.startsWith("pdraw ")
        val value = if (command.startsWith("pdraw ")) "/" else command
        return HelpChat.text(
            command,
            color = color,
            clickKind = if (isCommand) HelpClick.SUGGEST else HelpClick.COPY,
            command = value,
            hover = translation(if (isCommand) "command.help.ui.click_fill" else "command.help.ui.click_copy").component(),
        )
    }

    fun footer(buttons: List<HelpButton>): Component = buttonsRow(buttons)
}
