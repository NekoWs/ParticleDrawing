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
