package work.nekow.particledrawing.command.v2

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.Coordinates
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.commands.arguments.selector.EntitySelector
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.command.CommandNamePattern
import work.nekow.particledrawing.command.CommandSuggestionCatalog
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.translation
import java.util.concurrent.CompletableFuture

internal class CommandInput(
    raw: String,
    private val suggesting: Boolean = false,
    val suggestionSource: CommandSourceStack? = null,
) {
    private val reader = StringReader(raw)
    private var suggestionStart = 0
    private val candidates = linkedSetOf<String>()
    private var catalog: CommandSuggestionCatalog? = null
    private var catalogStart = -1
    private var selectorStart = -1

    private fun start(): Int {
        reader.skipWhitespace()
        return reader.cursor
    }

    fun suggest(choices: Iterable<String>) {
        val from = completionStart() ?: return
        val prefix = reader.remaining
        offer(from, choices.filter {
            it.startsWith(prefix, ignoreCase = true) || it.removePrefix("\"").startsWith(prefix, ignoreCase = true)
        })
    }

    private fun completionStart(): Int? {
        if (!suggesting) return null
        val from = start()
        if (from == reader.totalLength && from > 0 && !reader.string[from - 1].isWhitespace()) return null
        if (!reader.remaining.startsWith('"') && reader.remaining.any(Char::isWhitespace)) return null
        return from
    }

    fun clientValues(kind: CommandSuggestionCatalog) {
        catalogStart = completionStart() ?: return
        catalog = kind
    }

    fun valueSuggestions(choices: Iterable<String>) {
        val from = completionStart() ?: return
        offer(from, choices.mapNotNull { CommandSuggestionCatalog.valueCandidate(it, reader.remaining) })
    }

    fun valueSuggestions(choices: () -> Iterable<String>) {
        val from = completionStart() ?: return
        offer(from, choices().mapNotNull { CommandSuggestionCatalog.valueCandidate(it, reader.remaining) })
    }

    private fun offer(from: Int, choices: Iterable<String>) {
        if (from > suggestionStart) {
            suggestionStart = from
            candidates.clear()
        }
        if (from == suggestionStart) candidates += choices
    }

    fun suggestions(builder: SuggestionsBuilder): SuggestionsBuilder =
        builder.createOffset(builder.start + suggestionStart).also { result -> candidates.forEach { result.suggest(it) } }

    fun buildSuggestions(
        builder: SuggestionsBuilder, context: CommandContext<CommandSourceStack>,
    ): CompletableFuture<Suggestions> {
        val base = suggestions(builder).build()
        val kind = catalog
        val regular = if (kind != null && catalogStart >= suggestionStart) {
            Suggestions.create(builder.input, base.list + kind.marker(builder.input, builder.start + catalogStart))
        } else base
        if (selectorStart < suggestionStart) return CompletableFuture.completedFuture(regular)
        return EntityArgument.entity().listSuggestions(context, builder.createOffset(builder.start + selectorStart))
            .thenApply { Suggestions.merge(builder.input, listOf(regular, it)) }
    }

    fun peek(): String {
        val from = start()
        if (!reader.canRead() || reader.peek() == '"') return ""
        while (reader.canRead() && !reader.peek().isWhitespace()) reader.skip()
        return reader.string.substring(from, reader.cursor).also { reader.cursor = from }
    }

    fun take(word: String): Boolean {
        suggest(listOf(word))
        if (peek() != word) return false
        reader.cursor += word.length
        return true
    }

    fun expect(word: String) {
        if (!take(word)) invalid(word)
    }

    fun choice(choices: Collection<String>, label: String = choices.joinToString("/"), candidates: Collection<String> = choices): String {
        suggest(candidates)
        return peek().takeIf { it in choices }?.also { reader.cursor += it.length } ?: invalid(label)
    }

    fun value(label: String, choices: Collection<String> = emptyList()): String {
        if (choices.isNotEmpty()) valueSuggestions(choices)
        val from = start()
        if (!reader.canRead()) invalid(label)
        if (reader.peek() == '"') {
            val value = readQuoted(label)
            if (reader.canRead() && !reader.peek().isWhitespace()) invalid(label)
            return value
        }
        while (reader.canRead() && !reader.peek().isWhitespace()) {
            if (reader.peek() == '"') invalid(label)
            reader.skip()
        }
        return reader.string.substring(from, reader.cursor)
    }

    /** 引号字符串：除 `\"` 与 `\\` 外也接受 `\n`、`\t`，与脚本文本使用同一套写法。 */
    private fun readQuoted(label: String): String {
        reader.skip()
        val out = StringBuilder()
        while (reader.canRead()) {
            when (val c = reader.read()) {
                '"' -> return out.toString()
                '\\' -> {
                    if (!reader.canRead()) invalid(label)
                    when (val escaped = reader.read()) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> invalid("escape sequence \\$escaped")
                    }
                }
                else -> out.append(c)
            }
        }
        invalid(label)
    }

    fun name(choices: Collection<String> = emptyList()): String {
        suggestNames(choices)
        return value("name").also {
            if (!NAME.matches(it)) invalid("name", it)
        }
    }

    fun namePattern(choices: Collection<String>): CommandNamePattern {
        suggestNames(choices + "*")
        return value("name pattern").let {
            CommandNamePattern.parse(it) ?: invalid("name pattern", it)
        }
    }

    private fun suggestNames(choices: Collection<String>) {
        if (!suggesting) return
        val quoted = reader.string.getOrNull(start()) == '"'
        suggest(choices.map { if (quoted || it in RESERVED) quote(it) else it })
    }

    fun integer(label: String, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int =
        value(label).let { token -> token.toIntOrNull()?.takeIf { it in min..max } ?: invalid("$label ($min..$max)", token) }

    fun number(label: String): Double =
        value(label).let { token -> token.toDoubleOrNull()?.takeIf { it.isFinite() && DECIMAL.matches(token) } ?: invalid(label, token) }

    fun vector(label: String): Vec3 = Vec3(number(label), number(label), number(label))

    fun coordinates(): Coordinates {
        val from = start()
        suggest(listOf("~ ~ ~", "^ ^ ^"))
        if (suggesting && reader.canRead() && reader.peek() in "~^") {
            val prefix = reader.remaining
            val parts = prefix.trimEnd().split(Regex("\\s+"))
            val marker = reader.peek()
            if (parts.size in 1..2 && parts.all { it.startsWith(marker) }) {
                val filled = parts + List(3 - parts.size) { marker.toString() }
                offer(from, listOf(filled.joinToString(" ")))
            }
        }
        return Vec3Argument.vec3().parse(reader)
    }

    fun entity(): EntitySelector {
        suggest(listOf("@s", "@p", "@a[limit=1]", "@e[limit=1]"))
        val from = start()
        val selector = try {
            EntityArgument.entity().parse(reader)
        } catch (error: CommandSyntaxException) {
            if (suggesting && (from < reader.totalLength || completionStart() != null)) selectorStart = from
            throw error
        }
        if (suggesting && !reader.canRead()) selectorStart = from
        return selector
    }

    fun formula(label: String): String =
        value(label).takeIf { it.isNotBlank() && it.length <= 2048 } ?: invalid("$label (1..2048)")

    fun flag(): Boolean {
        suggest(BOOLEANS)
        return if (peek() in BOOLEANS) boolean() else true
    }

    fun boolean(): Boolean = choice(BOOLEANS) == "true"

    fun end(): Boolean {
        val dryrun = take("dryrun")
        finish()
        return dryrun
    }

    /** 当前游标（跳过空白），配合 [rawSince] 截取刚解析完的原文，用于保存程序模板。 */
    fun mark(): Int = start()

    /** 从 [mark] 到当前位置的原始文本；模板展开时先替换参数再重新解析。 */
    fun rawSince(mark: Int): String = reader.string.substring(mark, reader.cursor).trim()

    /** 当前位置之后的原始文本。 */
    fun remainingRaw(): String = reader.string.substring(start())

    /** 取走剩余原文并走到命令末尾，供程序模板保存动作段。 */
    fun consumeRemaining(): String {
        val text = remainingRaw().trim()
        reader.cursor = reader.totalLength
        return text
    }

    fun finish() {
        start()
        if (reader.canRead()) invalid("end of command", reader.remaining)
    }

    fun invalid(expected: String, actual: String = peek()): Nothing {
        throw SimpleCommandExceptionType(translation("error.command.v2.expected", expected, actual).component())
            .createWithContext(reader)
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9_.:-]{1,64}")
        private val DECIMAL = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        private val BOOLEANS = listOf("true", "false")
        private val RESERVED = setOf("list", "clear_all")

        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t") + "\""
    }
}

internal fun EntitySelector.resolve(source: CommandSourceStack) = findSingleEntity(source)

internal object CommandLook {
    private val toggles = setOf("glow", "additive", "billboard", "light_only", "align_velocity")
    private val widths = mapOf("uv" to 4, "spin" to 3, "light_dir" to 2, "trail_width" to 2)
    private val aliases = mapOf("glowing" to "glow", "intensity" to "light_intensity")
    private val legacy = mapOf("glow" to "glowing", "light_intensity" to "intensity", "size_relative" to "size")
    val fields = (ParticleCommandOptionParser.appearanceKeys - "size" - aliases.keys) +
        aliases.values + setOf("size", "size_relative", "gradient")
    internal const val COLOR_EXPRESSION = "color.expr"

    fun key(field: String): String = aliases[field] ?: field

    fun slot(field: String): String = when (key(field)) {
        "color", "gradient", COLOR_EXPRESSION -> "color"
        "size", "size_world", "size_relative" -> "size"
        else -> key(field)
    }

    fun parse(input: CommandInput, allowed: Set<String> = fields): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val seen = hashSetOf<String>()
        do {
            val candidates = (fields + aliases.keys).filter { key(it) in allowed && slot(it) !in seen }
            val field = key(input.choice(fields + aliases.keys, candidates = candidates))
            if (!seen.add(slot(field))) input.invalid("distinct look field", field)
            when {
                field in toggles -> values[field] = input.flag().toString()
                field == "color" && input.take("expr") -> values[COLOR_EXPRESSION] = input.formula("color")
                field == "gradient" -> values[field] = List(2) { input.value("color", ParticleCommandOptionParser.colorNames) }.joinToString(":")
                field == "color" -> values[field] = input.value("color", ParticleCommandOptionParser.colorNames)
                field == "style" -> values[field] = input.choice(listOf("square", "soft_dot", "line"))
                field == "spin_space" -> values[field] = input.choice(listOf("world", "local"))
                field == "texture" -> {
                    input.clientValues(CommandSuggestionCatalog.TEXTURE)
                    input.valueSuggestions(CommandSuggestionCatalog::textureNames)
                    values[field] = input.value(field)
                }
                field == "model" -> {
                    input.clientValues(CommandSuggestionCatalog.MODEL)
                    values[field] = input.value(field, listOf("none", "particledrawing:builtin/octahedron"))
                }
                field == "light_falloff" -> values[field] = input.value(field, LightFalloff.entries.map { it.name.lowercase() })
                field == "light" -> values[field] = input.value(field, listOf("0", "7", "15"))
                field == "size" -> values["size_world"] = input.number(field).let { "$it,$it" }
                field in setOf("size_world", "size_relative") -> values[field] = List(2) { input.number(field) }.joinToString(",")
                field == "trail_color" -> values[field] = List(2) {
                    input.value(field, ParticleCommandOptionParser.colorNames + "inherit")
                }.joinToString(":")
                field in widths -> values[field] = List(widths.getValue(field)) { input.number(field) }.joinToString(",")
                else -> values[field] = input.value(field)
            }
            input.suggest((fields + aliases.keys).filter { key(it) in allowed && slot(it) !in seen })
        } while (input.peek() in fields || input.peek() in aliases)
        return values
    }

    fun options(values: Map<String, String>, extras: Map<String, String> = emptyMap()) =
        ParticleCommandOptionParser.parse((values.filterKeys { it != COLOR_EXPRESSION } + extras).entries
            .joinToString(" ") { (key, value) -> "${legacy[key] ?: key}=${CommandInput.quote(value)}" })
}
