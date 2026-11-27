package work.nekow.particledrawing.core.client

import com.mojang.brigadier.suggestion.Suggestions
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.ResourceManager
import work.nekow.particledrawing.api.ParticleTextures
import work.nekow.particledrawing.command.CommandSuggestionCatalog
import work.nekow.particledrawing.platform.toMinecraft

/** 只有本地资源管理器能看到玩家当前启用的资源包。 */
internal object ClientCommandSuggestions {
    private val index = mutableMapOf<CommandSuggestionCatalog, List<String>>()

    fun invalidate() = index.clear()

    @JvmStatic
    fun expand(input: String, suggestions: Suggestions): Suggestions =
        CommandSuggestionCatalog.expand(input, suggestions, ::choices)

    private fun choices(kind: CommandSuggestionCatalog, prefix: String): Iterable<String> {
        if (kind == CommandSuggestionCatalog.CAMERA) return ClientAnimationManager.listCameras()
        val resources = Minecraft.getInstance().resourceManager
        val values = index.getOrPut(kind) {
            when (kind) {
                CommandSuggestionCatalog.TEXTURE ->
                    references(resources, "particles", ".json", "particle:") +
                        references(resources, "particle_sheets", ".json", "sheet:")
                CommandSuggestionCatalog.MODEL -> CommandSuggestionCatalog.resources(
                    resources, listOf("particle_models", "particle_meshes", "models", "meshes"), ".obj",
                )
                CommandSuggestionCatalog.KEYFRAMES -> references(resources, "particle_keyframes", ".pdg.gz")
                CommandSuggestionCatalog.CAMERA -> emptyList()
            }
        }
        if (kind != CommandSuggestionCatalog.TEXTURE) return values
        val framePrefix = prefix.removePrefix("\"")
        val modifier = framePrefix.indexOfFirst { it == '#' || it == '@' }
        if (modifier < 0) return values
        val reference = runCatching { ParticleTextures.parse(framePrefix.substring(0, modifier)) }.getOrNull()
            ?: return emptyList()
        val base = ParticleTextures.of(reference.particle.toString())
        if (base !in values) return emptyList()
        if (framePrefix[modifier] == '@') return listOf(8, 12, 20, 30, 60).map { "$base@$it" }
        val count = runCatching {
            ParticleTextures.resolveFrames(reference) { id -> resources.openAsReader(id.toMinecraft()).use { it.readText() } }.size
        }.getOrDefault(0)
        return (0 until count).map { "$base#$it" }
    }

    private fun references(
        resources: ResourceManager, directory: String, suffix: String, prefix: String = "",
    ): List<String> = resources.listResources(directory) { it.path.endsWith(suffix) }.keys.mapNotNull { id ->
        val name = "$prefix${id.namespace}:${id.path.removePrefix("$directory/").removeSuffix(suffix)}"
        name.takeIf { it.length <= 240 }
    }
}
