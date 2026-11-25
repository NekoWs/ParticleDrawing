package work.nekow.particledrawing.i18n

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

internal fun LocalizedText.component(): Component {
    val args = arguments.map {
        when (it) {
            is LocalizedText -> it.component()
            is Component -> it
            else -> it?.toString().orEmpty()
        }
    }
    return Component.translatableWithFallback(key, englishTemplate(), *args.toTypedArray())
}

internal fun Throwable.failureComponent(fallback: String): Component {
    val detail = diagnostic()
    return if (detail is LocalizedText) detail.component() else translation(fallback).component()
}

/**
 * 命令回显只写给命令源自身：玩家命令进聊天栏，命令方块只写方块输出，函数输出被原版抑制。
 * 永远不广播给其他玩家。
 */
internal fun CommandSourceStack.reply(block: () -> Component) {
    sendSuccess(block, false)
}

internal fun CommandSourceStack.reply(component: Component) = reply { component }

internal fun CommandSourceStack.replyFailure(component: Component) {
    sendFailure(component)
}
