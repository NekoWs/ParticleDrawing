package work.nekow.particledrawing.command.v2

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.Coordinates
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.commands.arguments.selector.EntitySelector
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.command.CommandNamePattern
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.translation

internal class CommandInput(raw: String, private val suggesting: Boolean = false) {
    private val reader = StringReader(raw)
    private var suggestionStart = 0
    private val candidates = linkedSetOf<String>()

    private fun start(): Int {
        reader.skipWhitespace()
        return reader.cursor
    }

    fun suggest(choices: Iterable<String>) {
        if (!suggesting) return
        val from = start()
        val prefix = reader.remaining
        if (prefix.any(Char::isWhitespace)) return
        offer(from, choices.filter {
            it.startsWith(prefix, ignoreCase = true) || it.removePrefix("\"").startsWith(prefix, ignoreCase = true)
        })
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

    fun choice(choices: Collection<String>, label: String = choices.joinToString("/")): String {
        suggest(choices)
        return peek().takeIf { it in choices }?.also { reader.cursor += it.length } ?: invalid(label)
    }

    fun value(label: String, choices: Collection<String> = emptyList()): String {
        suggest(choices)
        val from = start()
        if (!reader.canRead()) invalid(label)
        if (reader.peek() == '"') {
            val value = reader.readQuotedString()
            if (reader.canRead() && !reader.peek().isWhitespace()) invalid(label)
            return value
        }
        while (reader.canRead() && !reader.peek().isWhitespace()) {
            if (reader.peek() == '"') invalid(label)
            reader.skip()
        }
        return reader.string.substring(from, reader.cursor)
    }

    fun name(choices: Collection<String> = emptyList()): String =
        value("name", choices.map(::suggestedName)).also {
            if (!NAME.matches(it)) invalid("name", it)
        }

    fun namePattern(choices: Collection<String>): CommandNamePattern =
        value("name pattern", choices.map(::suggestedName) + "*").let {
            CommandNamePattern.parse(it) ?: invalid("name pattern", it)
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
        start()
        return EntityArgument.entity().parse(reader)
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
        private fun suggestedName(name: String) = if (name in RESERVED) quote(name) else name

        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
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

    fun parse(input: CommandInput): Map<String, String> {
        val values = linkedMapOf<String, String>()
        val seen = hashSetOf<String>()
        do {
            val field = key(input.choice(fields + aliases.keys))
            if (!seen.add(slot(field))) input.invalid("distinct look field", field)
            when {
                field in toggles -> values[field] = input.flag().toString()
                field == "color" && input.take("expr") -> values[COLOR_EXPRESSION] = input.formula("color")
                field == "gradient" -> values[field] = List(2) { input.value("color", ParticleCommandOptionParser.colorNames) }.joinToString(":")
                field == "color" -> values[field] = input.value("color", ParticleCommandOptionParser.colorNames)
                field == "style" -> values[field] = input.choice(listOf("square", "soft_dot", "line"))
                field == "spin_space" -> values[field] = input.choice(listOf("world", "local"))
                field == "size" -> values["size_world"] = input.number(field).let { "$it,$it" }
                field in setOf("size_world", "size_relative") -> values[field] = List(2) { input.number(field) }.joinToString(",")
                field == "trail_color" -> values[field] = List(2) { input.value(field) }.joinToString(":")
                field in widths -> values[field] = List(widths.getValue(field)) { input.number(field) }.joinToString(",")
                else -> values[field] = input.value(field)
            }
            input.suggest((fields + aliases.keys).filterNot { slot(it) in seen })
        } while (input.peek() in fields || input.peek() in aliases)
        return values
    }

    fun options(values: Map<String, String>, extras: Map<String, String> = emptyMap()) =
        ParticleCommandOptionParser.parse((values.filterKeys { it != COLOR_EXPRESSION } + extras).entries
            .joinToString(" ") { (key, value) -> "${legacy[key] ?: key}=${CommandInput.quote(value)}" })
}
