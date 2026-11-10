package work.nekow.particledrawing.api

import com.google.gson.JsonParser
import work.nekow.particledrawing.math.ResourceId as Identifier

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
        require(frame >= 0) { "粒子贴图帧号不能为负" }
        return checkedName("${referenceName(particle)}#$frame")
    }

    /** 固定帧率循环；未指定帧率时建议使用 [of] 让序列匹配粒子寿命。 */
    @JvmStatic
    fun animated(particle: String, fps: Double): String {
        require(fps.isFinite() && fps > 0 && fps <= 120) { "粒子贴图帧率需要大于 0 且不超过 120" }
        return checkedName("${referenceName(particle)}@${numberLiteral(fps)}")
    }

    private fun referenceName(particle: String): String {
        val id = Identifier.tryParse(particle)?.takeIf { it.path.isNotEmpty() }
            ?: throw IllegalArgumentException("无效的粒子资源名 '$particle'")
        return checkedName("$PREFIX$id")
    }

    private fun checkedName(name: String): String {
        require(name.length <= ParticleVisual.MAX_TEXTURE_NAME) { "粒子贴图引用过长" }
        return name
    }

    data class Reference(val particle: Identifier, val frame: Int? = null, val fps: Double? = null)

    fun parse(name: String): Reference? {
        if (!name.startsWith(PREFIX)) return null
        val raw = name.removePrefix(PREFIX)
        val parts = raw.split('#', '@')
        require(parts.size <= 2) { "粒子贴图引用格式为 particle:命名空间:名称[#帧号 或 @帧率]" }
        val id = Identifier.tryParse(parts[0])?.takeIf { it.path.isNotEmpty() }
            ?: throw IllegalArgumentException("无效的粒子资源名 '${parts[0]}'")
        if (parts.size == 1) return Reference(id)
        if ('@' in raw) {
            val fps = parts[1].toDoubleOrNull()
            require(fps != null && fps.isFinite() && fps > 0 && fps <= 120) { "粒子贴图帧率需要大于 0 且不超过 120" }
            return Reference(id, fps = fps)
        }
        val frame = parts[1].toIntOrNull()
            ?: throw IllegalArgumentException("粒子贴图帧号需要整数")
        require(frame >= 0) { "粒子贴图帧号不能为负" }
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
        require(textures != null && !textures.isEmpty) { "粒子 '$id' 没有可复用的贴图列表" }
        require(reference.frame == null || reference.frame < textures.size()) {
            "粒子 '$id' 只有 ${textures.size()} 帧贴图，无法读取第 ${reference.frame} 帧（从 0 开始）"
        }
        val selected = reference.frame?.let { listOf(textures[it]) } ?: textures.toList()
        require(selected.size <= 256) { "粒子 '$id' 的贴图序列超过 256 帧" }
        return selected.map {
            val sprite = Identifier.tryParse(it.asString)?.takeIf { id -> id.path.isNotEmpty() }
                ?: throw IllegalArgumentException("粒子 '$id' 的贴图资源名无效")
            Identifier.fromNamespaceAndPath(sprite.namespace, "textures/particle/${sprite.path}.png")
        }
    }
}
