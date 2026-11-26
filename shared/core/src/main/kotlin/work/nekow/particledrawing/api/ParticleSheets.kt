package work.nekow.particledrawing.api

import com.google.gson.JsonParser
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId

/** 资源包预排的粒子帧图集。图像按从左到右、从上到下的顺序存放。 */
internal object ParticleSheets {
    private const val PREFIX = "sheet:"
    fun isReference(name: String): Boolean = name.startsWith(PREFIX)

    data class Definition(val image: ResourceId, val width: Int, val height: Int, val frames: Int, val fps: Double)

    fun read(name: String, readDefinition: (ResourceId) -> String): Definition {
        fun validate(valid: Boolean) = requireLocalized(valid) { translation("error.texture.sheet", name) }
        val id = ResourceId.tryParse(name.removePrefix(PREFIX))
        validate(id != null && id.path.isNotEmpty())
        checkNotNull(id)
        val definition = ResourceId.fromNamespaceAndPath(id.namespace, "particle_sheets/${id.path}.json")
        val json = JsonParser.parseString(readDefinition(definition)).asJsonObject
        val width = json.get("frame_width")?.asString?.toIntOrNull() ?: 0
        val height = json.get("frame_height")?.asString?.toIntOrNull() ?: 0
        val frames = json.get("frames")?.asString?.toIntOrNull() ?: 0
        val fps = json.get("fps")?.asString?.toDoubleOrNull() ?: 0.0
        val image = json.get("texture")?.asString?.let(ResourceId.Companion::tryParse)
        validate(width in 1..4096 && height in 1..4096 && frames in 1..256 && fps > 0 && fps <= 120 &&
            image != null && image.path.startsWith("textures/") && image.path.endsWith(".png"))
        return Definition(checkNotNull(image), width, height, frames, fps)
    }
}
