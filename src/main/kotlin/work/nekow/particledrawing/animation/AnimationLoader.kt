package work.nekow.particledrawing.animation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import net.neoforged.fml.loading.FMLPaths
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.core.easing.EasingType
import java.nio.file.Files
import java.nio.file.Path

/**
 * 从磁盘读取并解析网页编辑器导出的动画 JSON。
 */
object AnimationLoader {

    /** 动画 JSON 存放目录：`<gameDir>/animations/`。 */
    val DIRECTORY: Path = FMLPaths.GAMEDIR.get().resolve("animations")

    /** 列出可用的动画名（不含 .json 后缀）。 */
    @JvmStatic
    fun list(): List<String> {
        if (!Files.isDirectory(DIRECTORY)) return emptyList()
        return Files.list(DIRECTORY).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }
                .sorted()
                .toList()
        }
    }

    /** 按名称读取动画 JSON 文本，找不到返回 null。 */
    @JvmStatic
    fun load(name: String): String? {
        val safe = name.replace(Regex("[^a-zA-Z0-9_\\-.]"), "_")
        val path = DIRECTORY.resolve("$safe.json")
        if (!Files.exists(path)) return null
        return Files.readString(path)
    }

    /** 解析动画 JSON 文本。 */
    @JvmStatic
    fun parse(json: String): ParticleAnimation {
        val root = JsonParser.parseString(json).asJsonObject
        val loop = root.get("loop")?.asBoolean ?: false

        val particles = root.get("p")?.asJsonArray
            ?.map { parseParticle(it.asJsonObject) }
            ?: emptyList()

        val tracks = root.get("t")?.asJsonArray
            ?.map { parseTrack(it.asJsonObject) }
            ?: emptyList()

        val groups = mutableMapOf<String, List<String>>()
        root.get("g")?.asJsonObject?.entrySet()?.forEach { (name, members) ->
            groups[name] = members.asJsonArray.map { it.asString }
        }

        return ParticleAnimation(loop, particles, tracks, groups)
    }

    private fun parseParticle(o: com.google.gson.JsonObject): AnimParticle {
        val id = o.get("id")?.asString ?: throw IllegalArgumentException("粒子缺少 id")
        val style = ParticleStyle.valueOf(o.get("s").asString.uppercase())
        val c = o.get("c").asJsonArray
        val color = Color.of(c[0].asFloat, c[1].asFloat, c[2].asFloat, c[3].asFloat)
        val scale = o.get("sc")?.asFloat ?: 1f
        val glowing = o.get("g")?.asInt == 1
        val lightLevel = o.get("l")?.asInt ?: 0
        val p = o.get("pos").asJsonArray
        val pos = Vec3(p[0].asDouble, p[1].asDouble, p[2].asDouble)
        val velArr = o.get("vel")?.asJsonArray
        val vel = if (velArr != null) Vec3(velArr[0].asDouble, velArr[1].asDouble, velArr[2].asDouble) else Vec3.ZERO
        return AnimParticle(id, style, color, scale, glowing, lightLevel, pos, vel)
    }

    private fun parseTrack(o: com.google.gson.JsonObject): AnimTrack {
        val property = AnimTrack.Property.from(o.get("pr").asString)
        val ids = o.get("ids").asJsonArray.map { it.asString }
        val mode = if (o.get("m")?.asString == "op") AnimTrack.Mode.OP else AnimTrack.Mode.SET
        val keyframes = o.get("kf").asJsonArray
            .map { parseKeyframe(it.asJsonArray, property) }
            .sortedBy { it.tick }
        return AnimTrack(property, ids, keyframes, mode)
    }

    private fun parseKeyframe(arr: JsonArray, property: AnimTrack.Property): AnimKeyframe {
        val tick = arr[0].asInt
        val valueArr = arr[1].asJsonArray
        val value = DoubleArray(valueArr.size()) { i ->
            val v = valueArr[i].asDouble
            if (property == AnimTrack.Property.ROTATION) v * Math.PI / 180.0 else v
        }
        val easing = parseEasing(arr[2])
        return AnimKeyframe(tick, value, easing)
    }

    private fun parseEasing(el: JsonElement): EasingType {
        if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) {
            val idx = el.asInt
            if (idx in EasingType.PRESETS.indices) return EasingType.PRESETS[idx]
            return EasingType.LINEAR
        }
        if (el.isJsonArray) {
            val a = el.asJsonArray
            return EasingType.custom(a[0].asDouble, a[1].asDouble, a[2].asDouble, a[3].asDouble)
        }
        return EasingType.LINEAR
    }
}
