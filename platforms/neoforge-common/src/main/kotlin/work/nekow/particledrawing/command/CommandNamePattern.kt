package work.nekow.particledrawing.command

import net.minecraft.commands.CommandSourceStack
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.translation

/** Whole-name, case-sensitive glob. All characters other than '*' and '?' are literal. */
internal class CommandNamePattern private constructor(val text: String) {
    val isWildcard = '*' in text || '?' in text

    fun select(names: Collection<String>): List<String> = if (isWildcard) names.filter(::matches) else listOf(text)

    fun report(source: CommandSourceStack, count: Int): Int {
        source.reply({ translation("command.v2.matches", text, count).component() })
        return count
    }

    fun matches(name: String): Boolean {
        var input = 0
        var pattern = 0
        var star = -1
        var retry = 0
        while (input < name.length) {
            when {
                pattern < text.length && text[pattern] == '*' -> {
                    star = pattern++
                    retry = input
                }
                pattern < text.length && (text[pattern] == '?' || text[pattern] == name[input]) -> {
                    input++
                    pattern++
                }
                star >= 0 -> {
                    pattern = star + 1
                    input = ++retry
                }
                else -> return false
            }
        }
        while (pattern < text.length && text[pattern] == '*') pattern++
        return pattern == text.length
    }

    companion object {
        private val SYNTAX = Regex("[A-Za-z0-9_./:*?-]{1,128}")

        fun parse(text: String): CommandNamePattern? =
            text.takeIf(SYNTAX::matches)?.let(::CommandNamePattern)
    }
}
