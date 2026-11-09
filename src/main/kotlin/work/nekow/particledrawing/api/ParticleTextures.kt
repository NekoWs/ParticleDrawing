package work.nekow.particledrawing.api

import com.google.gson.JsonParser
import net.minecraft.resources.Identifier

/** 引用游戏资源包中的粒子贴图；仍由 PD 控制位置、颜色、寿命与动画。 */
object ParticleTextures {
    private const val PREFIX = "particle:"

    /**
     * 从 particles/<名称>.json 的 textures 列表选取一帧，默认第一帧。
     * 名称支持 minecraft 或其它模组命名空间；不复制原版粒子的物理与寿命逻辑。
     */
    @JvmStatic
    @JvmOverloads
    fun of(particle: String, frame: Int = 0): String {
        require(frame >= 0) { "粒子贴图帧号不能为负" }
        val id = Identifier.tryParse(particle)?.takeIf { it.path.isNotEmpty() }
            ?: throw IllegalArgumentException("无效的粒子资源名 '$particle'")
        val name = "$PREFIX$id" + if (frame == 0) "" else "#$frame"
        require(name.length <= ParticleVisual.MAX_TEXTURE_NAME) { "粒子贴图引用过长" }
        return name
    }

    internal data class Reference(val particle: Identifier, val frame: Int)

    internal fun parse(name: String): Reference? {
        if (!name.startsWith(PREFIX)) return null
        val parts = name.removePrefix(PREFIX).split('#')
        require(parts.size <= 2) { "粒子贴图引用格式为 particle:命名空间:名称[#帧号]" }
        val id = Identifier.tryParse(parts[0])?.takeIf { it.path.isNotEmpty() }
            ?: throw IllegalArgumentException("无效的粒子资源名 '${parts[0]}'")
        val frame = if (parts.size == 1) 0 else parts[1].toIntOrNull()
            ?: throw IllegalArgumentException("粒子贴图帧号需要整数")
        require(frame >= 0) { "粒子贴图帧号不能为负" }
        return Reference(id, frame)
    }

    /** 只解析资源映射，资源读取交给调用方，服务端无需加载客户端类。 */
    internal fun resolve(reference: Reference, readDefinition: (Identifier) -> String): Identifier {
        val id = reference.particle
        val definition = Identifier.fromNamespaceAndPath(id.namespace, "particles/${id.path}.json")
        val json = JsonParser.parseString(readDefinition(definition)).asJsonObject
        val textures = json.getAsJsonArray("textures")
        require(textures != null && !textures.isEmpty) { "粒子 '$id' 没有可复用的贴图列表" }
        require(reference.frame < textures.size()) {
            "粒子 '$id' 只有 ${textures.size()} 帧贴图，无法读取第 ${reference.frame} 帧（从 0 开始）"
        }
        val sprite = Identifier.tryParse(textures[reference.frame].asString)?.takeIf { it.path.isNotEmpty() }
            ?: throw IllegalArgumentException("粒子 '$id' 的贴图资源名无效")
        return Identifier.fromNamespaceAndPath(sprite.namespace, "textures/particle/${sprite.path}.png")
    }
}
