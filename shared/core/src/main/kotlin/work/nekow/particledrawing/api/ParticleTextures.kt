package work.nekow.particledrawing.api

import com.google.gson.JsonParser
import work.nekow.particledrawing.math.ResourceId as Identifier
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 引用游戏资源包中的粒子贴图；仍由 PD 控制位置、颜色、寿命与动画。 */
object ParticleTextures {
    private const val PREFIX = "particle:"

    /**
     * 从 particles/<名称>.json 复用帧序列；有限寿命按年龄播放，无限寿命循环。
     * 名称支持 minecraft 或其它模组命名空间；不复制原版粒子的物理与寿命逻辑。
     */
    @JvmStatic
    fun of(particle: String): String = referenceName(particle)

    /** 显式选择静态帧，帧号从 0 开始。 */
    @JvmStatic
    fun of(particle: String, frame: Int): String {
        requireLocalized(frame >= 0) { translation("error.texture.frame_nonnegative") }
        return checkedName("${referenceName(particle)}#$frame")
    }

    /** 固定帧率循环；未指定帧率时建议使用 [of] 让序列匹配粒子寿命。 */
    @JvmStatic
    fun animated(particle: String, fps: Double): String {
        requireLocalized(fps.isFinite() && fps > 0 && fps <= 120) { translation("error.texture.fps") }
        return checkedName("${referenceName(particle)}@${numberLiteral(fps)}")
    }

    private fun referenceName(particle: String): String {
        val id = Identifier.tryParse(particle)?.takeIf { it.path.isNotEmpty() }
            ?: throw LocalizedArgumentException(translation("error.texture.resource_name", particle))
        return checkedName("$PREFIX$id")
    }

    private fun checkedName(name: String): String {
        requireLocalized(name.length <= ParticleVisual.MAX_TEXTURE_NAME) { translation("error.texture.reference_length") }
        return name
    }

    data class Reference(val particle: Identifier, val frame: Int? = null, val fps: Double? = null)

    fun parse(name: String): Reference? {
        if (!name.startsWith(PREFIX)) return null
        val raw = name.removePrefix(PREFIX)
        val parts = raw.split('#', '@')
        requireLocalized(parts.size <= 2) { translation("error.texture.reference_syntax") }
        val id = Identifier.tryParse(parts[0])?.takeIf { it.path.isNotEmpty() }
            ?: throw LocalizedArgumentException(translation("error.texture.resource_name", parts[0]))
        if (parts.size == 1) return Reference(id)
        if ('@' in raw) {
            val fps = parts[1].toDoubleOrNull()
            requireLocalized(fps != null && fps.isFinite() && fps > 0 && fps <= 120) { translation("error.texture.fps") }
            return Reference(id, fps = fps)
        }
        val frame = parts[1].toIntOrNull()
            ?: throw LocalizedArgumentException(translation("error.texture.frame_integer"))
        requireLocalized(frame >= 0) { translation("error.texture.frame_nonnegative") }
        return Reference(id, frame)
    }

    /** 只解析资源映射，资源读取交给调用方，服务端无需加载客户端类。 */
    fun resolve(reference: Reference, readDefinition: (Identifier) -> String): Identifier {
        return resolveFrames(reference, readDefinition).first()
    }

    fun resolveFrames(reference: Reference, readDefinition: (Identifier) -> String): List<Identifier> {
        val id = reference.particle
        val definition = Identifier.fromNamespaceAndPath(id.namespace, "particles/${id.path}.json")
        val json = JsonParser.parseString(readDefinition(definition)).asJsonObject
        val textures = json.getAsJsonArray("textures")
        requireLocalized(textures != null && !textures.isEmpty) { translation("error.texture.missing_frames", id) }
        requireLocalized(reference.frame == null || reference.frame < textures.size()) {
            translation("error.texture.frame_range", id, textures.size(), reference.frame)
        }
        val selected = reference.frame?.let { listOf(textures[it]) } ?: textures.toList()
        requireLocalized(selected.size <= 256) { translation("error.texture.sequence_length", id) }
        return selected.map {
            val sprite = Identifier.tryParse(it.asString)?.takeIf { id -> id.path.isNotEmpty() }
                ?: throw LocalizedArgumentException(translation("error.texture.sequence_resource", id))
            Identifier.fromNamespaceAndPath(sprite.namespace, "textures/particle/${sprite.path}.png")
        }
    }
}
