package work.nekow.particledrawing.i18n

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
    if (detail is LocalizedText) return detail.component()
    val label = translation(fallback)
    return if (detail == null) label.component() else translation("error.detail", label, detail).component()
}
