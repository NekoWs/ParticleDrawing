package work.nekow.particledrawing.i18n

import com.google.gson.JsonParser
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizedTextTest {
    @Test
    fun catalogsUseTheSameKeysAndArguments() {
        val english = catalog("en_us")
        val chinese = catalog("zh_cn")
        assertEquals(english.keys, chinese.keys)

        val placeholder = Regex("%([1-9][0-9]*)[$]s")
        fun indexes(template: String) = placeholder.findAll(template).map { it.groupValues[1].toInt() }.toSet()

        for ((key, englishTemplate) in english) {
            val englishArgs = indexes(englishTemplate)
            val chineseTemplate = chinese.getValue(key)
            assertEquals(englishArgs, indexes(chineseTemplate), key)
            val arguments = List(englishArgs.maxOrNull() ?: 0) { "⟦${it + 1}⟧" }
            val text = LocalizedText(key, arguments)
            for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
                val rendered = text.resolve(locale)
                assertTrue(englishArgs.all { arguments[it - 1] in rendered }, key)
            }
        }
    }

    private fun catalog(locale: String): Map<String, String> =
        checkNotNull(javaClass.getResourceAsStream("/assets/particledrawing/lang/$locale.json"))
            .bufferedReader(Charsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader).asJsonObject.entrySet().associate { it.key to it.value.asString }
            }
    @Test
    fun missingKeyFallsBackToTheKeyInsteadOfThrowing() {
        val text = LocalizedText("particledrawing.missing.key", emptyList())
        assertTrue(text.resolve(Locale.ENGLISH).contains("missing.key"))
        assertTrue(text.resolve(Locale.SIMPLIFIED_CHINESE).contains("missing.key"))
    }
}
