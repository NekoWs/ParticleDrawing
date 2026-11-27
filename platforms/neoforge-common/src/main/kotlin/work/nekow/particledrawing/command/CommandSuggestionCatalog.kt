package work.nekow.particledrawing.command

import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.resources.ResourceManager
import work.nekow.particledrawing.command.v2.CommandInput
import work.nekow.particledrawing.core.TextureRegistry
import work.nekow.particledrawing.util.BuiltinTextures

/** 服务端只圈定参数范围，客户端用自己的资源包补全。 */
internal enum class CommandSuggestionCatalog {
    TEXTURE, MODEL, KEYFRAMES, CAMERA;

    private val tooltip = "particledrawing:suggestions/${name.lowercase()}"

    fun marker(input: String, start: Int): Suggestion =
        Suggestion(StringRange.between(start, input.length), input.substring(start), Component.literal(tooltip))

    companion object {
        fun textureNames(): List<String> =
            (TextureRegistry.all().map { it.name } + BuiltinTextures.KEYFRAME_GLOW).distinct().sorted()

        fun resources(manager: ResourceManager, directories: List<String>, suffix: String): List<String> =
            directories.flatMap { directory ->
                manager.listResources(directory) { it.path.endsWith(suffix) }.keys.map { it.toString() }
            }.distinct().sorted()

        fun valueCandidate(value: String, prefix: String): String? {
            val candidate = if (prefix.startsWith('"') || value.any { it.isWhitespace() || it == '"' }) {
                CommandInput.quote(value)
            } else value
            return candidate.takeIf {
                it.startsWith(prefix, ignoreCase = true) ||
                    (!prefix.startsWith('"') && it.removePrefix("\"").startsWith(prefix, ignoreCase = true))
            }
        }

        fun suggestBrigadierStrings(builder: SuggestionsBuilder, values: Iterable<String>): SuggestionsBuilder {
            for (value in values) brigadierString(value, builder.remaining)?.let { builder.suggest(it) }
            return builder
        }

        private fun brigadierString(value: String, prefix: String): String? {
            val candidate = if (prefix.startsWith('"')) CommandInput.quote(value) else StringArgumentType.escapeIfRequired(value)
            return candidate.takeIf { it.startsWith(prefix, ignoreCase = true) ||
                (!prefix.startsWith('"') && it.removePrefix("\"").startsWith(prefix, ignoreCase = true)) }
        }

        fun expand(input: String, suggestions: Suggestions, choices: (CommandSuggestionCatalog, String) -> Iterable<String>): Suggestions {
            val markers = suggestions.list.mapNotNull { suggestion ->
                entries.firstOrNull { it.tooltip == suggestion.tooltip?.string }?.let { kind -> suggestion to kind }
            }
            if (markers.isEmpty()) return suggestions
            val result = suggestions.list.toMutableSet()
            for ((marker, kind) in markers) {
                result.remove(marker)
                val range = marker.range
                if (range.start < 0 || range.end > input.length) continue
                val prefix = range.get(input)
                for (value in choices(kind, prefix)) {
                    val direct = if (kind == CAMERA) brigadierString(value, prefix) else valueCandidate(value, prefix)
                    val text = direct ?: when {
                        kind != TEXTURE || !(value.startsWith("particle:") || value.startsWith("sheet:")) -> null
                        else -> {
                            val scheme = value.substringBefore(':')
                            val id = value.substringAfter(':')
                            val path = id.substringAfter(':')
                            if (listOf(id, path, "$scheme:$path").any { valueCandidate(it, prefix) != null })
                                if (prefix.startsWith('"')) CommandInput.quote(value) else value
                            else null
                        }
                    } ?: continue
                    if (text != prefix) result.add(Suggestion(range, text))
                }
            }
            return Suggestions.create(input, result)
        }
    }
}
