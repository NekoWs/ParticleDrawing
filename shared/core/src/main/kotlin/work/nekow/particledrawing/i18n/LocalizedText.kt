package work.nekow.particledrawing.i18n

import com.google.gson.JsonParser
import java.util.Locale
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

internal class LocalizedText(val key: String, val arguments: List<Any?>) {
    /**
     * 英文模板。
     *
     * 缺键时回退成键名本身，而不是抛异常：漏翻或拼错键只会让玩家看到一句不友好的原文，
     * 不会把整条命令变成失败。键名以 `particledrawing.` 开头，一眼能看出是缺失的翻译。
     */
    fun englishTemplate(): String = English.entries[key] ?: key

    fun resolve(locale: Locale = Locale.getDefault()): String {
        val template = if (locale.language == "zh") Chinese.entries[key] ?: englishTemplate() else englishTemplate()
        if (arguments.isEmpty() && '%' !in template) return template
        val args = arguments.map { (it as? LocalizedText)?.resolve(locale) ?: it }.toTypedArray()
        return String.format(Locale.ROOT, template, *args)
    }

    private object English { val entries = readLanguage("en_us") }
    private object Chinese { val entries = readLanguage("zh_cn") }
}

private fun readLanguage(language: String): Map<String, String> {
    val path = "/assets/particledrawing/lang/$language.json"
    val stream = checkNotNull(LocalizedText::class.java.getResourceAsStream(path)) { "Missing language file $path" }
    return stream.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        .entrySet().associate { it.key to it.value.asString }
}

internal fun translation(key: String, vararg arguments: Any?): LocalizedText =
    LocalizedText("particledrawing.$key", arguments.toList())

internal interface LocalizedFailure { val text: LocalizedText }

internal fun Throwable.diagnostic(): Any? =
    (this as? LocalizedFailure)?.text ?: message?.takeIf { it.isNotBlank() }

internal class LocalizedArgumentException(override val text: LocalizedText, cause: Throwable? = null) :
    IllegalArgumentException(text.resolve(), cause), LocalizedFailure

internal class LocalizedStateException(override val text: LocalizedText, cause: Throwable? = null) :
    IllegalStateException(text.resolve(), cause), LocalizedFailure

@OptIn(ExperimentalContracts::class)
internal inline fun requireLocalized(value: Boolean, text: () -> LocalizedText) {
    contract { returns() implies value }
    if (!value) throw LocalizedArgumentException(text())
}

@OptIn(ExperimentalContracts::class)
internal inline fun checkLocalized(value: Boolean, text: () -> LocalizedText) {
    contract { returns() implies value }
    if (!value) throw LocalizedStateException(text())
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T : Any> requireNotNullLocalized(value: T?, text: () -> LocalizedText): T {
    contract { returns() implies (value != null) }
    return value ?: throw LocalizedArgumentException(text())
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T : Any> checkNotNullLocalized(value: T?, text: () -> LocalizedText): T {
    contract { returns() implies (value != null) }
    return value ?: throw LocalizedStateException(text())
}

internal fun localizedError(text: LocalizedText): Nothing = throw LocalizedStateException(text)
