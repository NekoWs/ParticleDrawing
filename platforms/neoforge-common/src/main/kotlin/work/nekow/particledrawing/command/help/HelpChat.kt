package work.nekow.particledrawing.command.help

import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import work.nekow.particledrawing.i18n.LocalizedText
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.translation
import java.lang.reflect.Constructor

/** 点击行为：执行命令、把命令填进聊天框、复制到剪贴板。 */
internal enum class HelpClick { RUN, SUGGEST, COPY }

/**
 * 聊天帮助的组件构造层。
 *
 * 26.x 把 ClickEvent / HoverEvent 改成了带 record 的接口，1.21.1 还是带 Action 枚举的类；
 * 这里用少量反射把两种形状统一成一套入口，帮助内容的代码不必分版本。
 */
internal object HelpChat {
    /** 帮助页的版心宽度（GUI 像素），页眉和页脚按这个宽度对齐。 */
    private const val PAGE_WIDTH = 300

    private val clickEventClass = ClickEvent::class.java
    private val hoverEventClass = HoverEvent::class.java

    private val modernClick = clickEventClass.isInterface
    private val modernHover = hoverEventClass.isInterface

    private val clickConstructors: Map<HelpClick, Constructor<*>> by lazy {
        if (modernClick) {
            mapOf(
                HelpClick.RUN to classOf("net.minecraft.network.chat.ClickEvent\$RunCommand").getConstructor(String::class.java),
                HelpClick.SUGGEST to classOf("net.minecraft.network.chat.ClickEvent\$SuggestCommand").getConstructor(String::class.java),
                HelpClick.COPY to classOf("net.minecraft.network.chat.ClickEvent\$CopyToClipboard").getConstructor(String::class.java),
            )
        } else {
            val actionClass = classOf("net.minecraft.network.chat.ClickEvent\$Action")
            val constructor = clickEventClass.getConstructor(actionClass, String::class.java)
            mapOf(
                HelpClick.RUN to constructor,
                HelpClick.SUGGEST to constructor,
                HelpClick.COPY to constructor,
            )
        }
    }

    private val legacyClickActions: Map<HelpClick, Any> by lazy {
        if (modernClick) {
            emptyMap()
        } else {
            val constants = classOf("net.minecraft.network.chat.ClickEvent\$Action").enumConstants
            val names = mapOf(
                HelpClick.RUN to "RUN_COMMAND",
                HelpClick.SUGGEST to "SUGGEST_COMMAND",
                HelpClick.COPY to "COPY_TO_CLIPBOARD",
            )
            names.mapValues { (_, name) -> constants.first { (it as Enum<*>).name == name } }
        }
    }

    private val modernHoverConstructor: Constructor<*> by lazy {
        classOf("net.minecraft.network.chat.HoverEvent\$ShowText").getConstructor(Component::class.java)
    }

    private val legacyHoverConstructor: Constructor<*> by lazy {
        hoverEventClass.declaredConstructors.first { constructor ->
            constructor.parameterCount == 2 &&
                constructor.parameterTypes[0].name == "net.minecraft.network.chat.HoverEvent\$Action"
        }.also { it.isAccessible = true }
    }

    private val legacyHoverAction: Any by lazy {
        // 1.21.1 的 HoverEvent.Action 是普通类，SHOW_TEXT 是静态字段而不是枚举常量。
        classOf("net.minecraft.network.chat.HoverEvent\$Action").getField("SHOW_TEXT").get(null)
    }

    private fun classOf(name: String): Class<*> = Class.forName(name)

    fun click(kind: HelpClick, value: String): ClickEvent {
        val constructor = clickConstructors.getValue(kind)
        return if (modernClick) {
            constructor.newInstance(value) as ClickEvent
        } else {
            constructor.newInstance(legacyClickActions.getValue(kind), value) as ClickEvent
        }
    }

    fun hover(text: Component): HoverEvent = if (modernHover) {
        modernHoverConstructor.newInstance(text) as HoverEvent
    } else {
        legacyHoverConstructor.newInstance(legacyHoverAction, text) as HoverEvent
    }

    fun text(
        content: String,
        color: ChatFormatting? = null,
        bold: Boolean = false,
        italic: Boolean = false,
        clickKind: HelpClick? = null,
        command: String? = null,
        hover: Component? = null,
    ): MutableComponent {
        var style = Style.EMPTY
        color?.let { style = style.withColor(it) }
        if (bold) style = style.withBold(true)
        if (italic) style = style.withItalic(true)
        if (clickKind != null && command != null) style = style.withClickEvent(click(clickKind, command))
        hover?.let { style = style.withHoverEvent(this.hover(it)) }
        return Component.literal(content).withStyle(style)
    }

    fun translated(key: String, vararg args: Any?): Component =
        translation(key, *args).component()

    /** 把多行文本压成一条聊天消息，行间用换行符。 */
    fun lines(parts: List<Component>): Component {
        val out = Component.empty()
        parts.forEachIndexed { index, part ->
            if (index > 0) out.append(Component.literal("\n"))
            out.append(part)
        }
        return out
    }

    fun message(source: CommandSourceStack, parts: List<Component>): Int {
        source.sendSuccess({ lines(parts) }, false)
        return 1
    }

    fun plain(source: CommandSourceStack, component: Component): Int {
        source.sendSuccess({ component }, false)
        return 1
    }

    /** 默认字体的近似宽度：中文等多字节字符 9px，空格 4px，其余 6px。 */
    private fun pixelWidth(text: String): Int = text.sumOf { char ->
        when {
            char.code >= 0x2E80 -> 9
            char == ' ' -> 4
            else -> 6
        }
    }

    /**
     * 页眉：`----------===<{ 标题 }>===----------`，总宽度固定为 [PAGE_WIDTH]。
     *
     * 不用固定条数，按标题（可能含中文）估算像素宽后均分两侧短横线，页脚用同样总宽的纯横线。
     */
    fun header(title: String): Component {
        val core = "===<{ $title }>==="
        val available = (PAGE_WIDTH - pixelWidth(core)).coerceAtLeast(12)
        val leftPixels = available / 2
        val left = "-".repeat(leftPixels / 6)
        val right = "-".repeat((available - leftPixels) / 6)
        return text(left, color = ChatFormatting.GRAY)
            .append(text("===<{ ", color = ChatFormatting.GRAY))
            .append(text(title, color = ChatFormatting.GOLD, bold = true))
            .append(text(" }>===", color = ChatFormatting.GRAY)
                .append(text(right, color = ChatFormatting.GRAY)))
    }

    /** 页脚分割线，宽度与页眉一致。 */
    fun bottom(): Component = text("-".repeat(PAGE_WIDTH / 6), color = ChatFormatting.GRAY)

    /** 旧调用点仍用这个名字。 */
    fun divider(): Component = bottom()

    /** 一行按钮，按钮之间用四格空格隔开。 */
    fun nav(parts: List<Component>): Component {
        val out = Component.empty()
        parts.forEachIndexed { index, part ->
            if (index > 0) out.append(text("    "))
            out.append(part)
        }
        return out
    }

    fun button(label: LocalizedText, command: String, hover: LocalizedText? = null): Component =
        text(
            "[" + label.resolve() + "]",
            color = ChatFormatting.AQUA,
            clickKind = HelpClick.RUN,
            command = command,
            hover = (hover ?: translation("command.help.ui.click_run")).component(),
        )

    fun disabled(label: String, hover: Component?): Component =
        text("[$label]", color = ChatFormatting.GRAY, hover = hover)

    /** 目录条目：`  - 标题：简介`，标题可点击。 */
    fun item(title: String, summary: String, command: String, hover: Component?): Component =
        text("  - ", color = ChatFormatting.GRAY)
            .append(
                text(
                    title,
                    color = ChatFormatting.AQUA,
                    bold = true,
                    clickKind = HelpClick.RUN,
                    command = command,
                    hover = hover,
                ),
            )
            .append(text("：" + summary, color = ChatFormatting.GRAY))

    /** 命令页的一行参数：`  - <名称>  效果说明`。 */
    fun param(name: String, effect: String): Component =
        text("  - ", color = ChatFormatting.GRAY)
            .append(text(name, color = ChatFormatting.YELLOW))
            .append(text("  " + effect, color = ChatFormatting.WHITE))

    /**
     * 可点击填入的命令行，缩进两格。
     *
     * 选择器、坐标占位符与 `键=` 各带悬停说明；`hovers` 是命令页从参数说明里拆出来的词条，
     * 例如 `<名称>` 会显示该参数的用途。
     */
    fun commandLine(command: String, indent: String = "  ", hovers: Map<String, String> = emptyMap()): Component {
        val value = if (command.startsWith("pdraw ")) "/$command" else command
        val out = text(indent, color = ChatFormatting.GRAY)
        command.split(' ').forEachIndexed { index, token ->
            if (index > 0) out.append(text(" "))
            val hover = tokenHover(token, hovers)
            val color = when {
                token.startsWith("<") -> ChatFormatting.YELLOW
                token.startsWith("@") -> ChatFormatting.LIGHT_PURPLE
                token == "~" || token.startsWith("~") || token.startsWith("^") -> ChatFormatting.AQUA
                else -> ChatFormatting.GREEN
            }
            out.append(
                text(
                    token,
                    color = color,
                    clickKind = HelpClick.SUGGEST,
                    command = value,
                    hover = hover ?: translation("command.help.ui.click_fill").component(),
                ),
            )
        }
        return out
    }

    private fun tokenHover(token: String, hovers: Map<String, String>): Component? {
        hovers[token]?.let { return Component.literal(it) }
        return when {
            token.startsWith("@p") -> translation("command.help.tip.at_p").component()
            token.startsWith("@a") -> translation("command.help.tip.at_a").component()
            token.startsWith("@e") -> translation("command.help.tip.at_e").component()
            token.startsWith("@s") -> translation("command.help.tip.at_s").component()
            token.startsWith("@r") -> translation("command.help.tip.at_r").component()
            token.startsWith("@") -> translation("command.help.tip.selector").component()
            token.startsWith("~") -> translation("command.help.tip.relative").component()
            token.startsWith("^") -> translation("command.help.tip.local").component()
            token == "kind" || token.startsWith("kind=") -> translation("command.help.tip.kind").component()
            '=' in token -> translation("command.help.tip.key_value").component()
            else -> null
        }
    }

    /** 小节标题，如「用法」「示例」「参数」。 */
    fun section(key: String): Component =
        text(translation(key).resolve() + "：", color = ChatFormatting.YELLOW)
}

/** 一行帮助里的按钮：显示文本、点击执行的命令、悬停说明。 */
internal data class HelpButton(
    val label: LocalizedText,
    val command: String,
    val click: HelpClick = HelpClick.RUN,
    val hover: LocalizedText? = null,
    val color: ChatFormatting = ChatFormatting.AQUA,
)

internal fun HelpButton.render(): Component = HelpChat.text(
    "[" + label.resolve() + "]",
    color = color,
    clickKind = click,
    command = command,
    hover = hover?.component(),
)

/** 用 ` · ` 连接一行里的多个按钮。 */
internal fun buttonsRow(buttons: List<HelpButton>): Component {
    val out = Component.empty()
    buttons.forEachIndexed { index, button ->
        if (index > 0) out.append(HelpChat.text(" · ", color = ChatFormatting.DARK_GRAY))
        out.append(button.render())
    }
    return out
}
