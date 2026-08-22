package work.nekow.particledrawing.animation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import net.neoforged.fml.loading.FMLPaths
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.animation.expr.Keyframe
import work.nekow.particledrawing.core.easing.EasingType
import java.nio.file.Files
import java.nio.file.Path

/**
 * 从磁盘读取并解析网页编辑器导出的动画 JSON。
 */
object AnimationLoader {

    /** 动画工程文件存放目录：`<gameDir>/animations/`。 */
    val DIRECTORY: Path = FMLPaths.GAMEDIR.get().resolve("animations")

    /** 列出可用的动画名（不含 .pdraw 后缀）。 */
    @JvmStatic
    fun list(): List<String> {
        if (!Files.isDirectory(DIRECTORY)) return emptyList()
        return Files.list(DIRECTORY).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".pdraw") }
                .map { it.fileName.toString().removeSuffix(".pdraw") }
                .sorted()
                .toList()
        }
    }

    /** 按名称读取动画工程文件文本，找不到返回 null。 */
    @JvmStatic
    fun load(name: String): String? {
        // 文件路径使用原始名称（支持中文等 Unicode 字符）
        val safe = name.replace(Regex("[/\\\\]"), "_")  // 仅防路径穿越，不剥离 Unicode
        val path = DIRECTORY.resolve("$safe.pdraw")
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

        val functions = root.get("f")?.asJsonArray
            ?.map { parseFunction(it.asJsonObject) }
            ?: emptyList()

        val textures = root.get("tex")?.asJsonArray
            ?.map { it.asString }
            ?: emptyList()

        val groupUV = mutableMapOf<String, UvData>()
        root.get("guv")?.asJsonObject?.entrySet()?.forEach { (name, uv) ->
            parseUv(uv)?.let { groupUV[name] = it }
        }

        // 内嵌贴图数据（v4+）：name → base64 PNG
        val texData = mutableMapOf<String, ByteArray>()
        root.get("texData")?.asJsonObject?.entrySet()?.forEach { (name, el) ->
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                try {
                    texData[name] = java.util.Base64.getDecoder().decode(el.asString)
                } catch (_: IllegalArgumentException) { /* 跳过无效 base64 */ }
            }
        }

        return ParticleAnimation(loop, particles, tracks, groups, functions, textures, groupUV, texData)
    }

    private fun parseFunction(o: JsonObject): FunctionObject {
        val id = o.get("id")?.asString ?: throw IllegalArgumentException("函数对象缺少 id")
        val name = o.get("name")?.asString ?: "函数对象"
        val center = o.get("center")?.asJsonArray?.map { it.asDouble }?.toDoubleArray() ?: doubleArrayOf(0.0, 0.0, 0.0)
        val count = o.get("count")?.asInt ?: 30
        val code = o.get("code")?.asString ?: ""
        val duration = o.get("duration")?.asInt ?: 0
        val step = o.get("step")?.asInt ?: 5
        val vars = mutableMapOf<String, FunctionVar>()
        o.get("vars")?.asJsonObject?.entrySet()?.forEach { (vname, v) ->
            val vo = v.asJsonObject
            val expr = vo.get("expr")?.asString ?: "0"
            val kf = vo.get("kf")?.asJsonArray?.map { parseVarKeyframe(it.asJsonArray) } ?: emptyList()
            vars[vname] = FunctionVar(expr, kf)
        }
        val uv = parseUv(o.get("uv"))
        return FunctionObject(id, name, center, count, code, vars, duration, step, uv)
    }

    private fun parseVarKeyframe(arr: JsonArray): Keyframe {
        val tick = arr[0].asDouble
        val value = arr[1].asDouble
        val easing = parseEasing(arr[2])
        return Keyframe(tick, value, easing)
    }

    private fun parseParticle(o: JsonObject): AnimParticle {
        val id = o.get("id")?.asString ?: throw IllegalArgumentException("粒子缺少 id")
        // c/g/l/vel 可省略（省略即默认值），与编辑器导出保持一致
        val cArr = o.get("c")?.asJsonArray
        val color = if (cArr != null) Color.of(cArr[0].asFloat, cArr[1].asFloat, cArr[2].asFloat, cArr[3].asFloat) else Color.of(1f, 1f, 1f, 1f)
        val scale = parseScale(o.get("sc"))
        val glowing = o.get("g")?.asInt == 1
        val lightLevel = o.get("l")?.asInt ?: 0
        val p = o.get("pos").asJsonArray
        val pos = Vec3(p[0].asDouble, p[1].asDouble, p[2].asDouble)
        val velArr = o.get("vel")?.asJsonArray
        val vel = if (velArr != null) Vec3(velArr[0].asDouble, velArr[1].asDouble, velArr[2].asDouble) else Vec3.ZERO
        val uv = parseUv(o.get("uv"))
        return AnimParticle(id, color, scale, glowing, lightLevel, pos, vel, uv)
    }

    /** 解析 scale：v3 为数组 [sx,sy,sz]，兼容旧版标量（扩展为 [v,v,v]）。 */
    private fun parseScale(el: JsonElement?): FloatArray {
        if (el == null) return floatArrayOf(1f, 1f, 1f)
        if (el.isJsonArray) {
            val a = el.asJsonArray
            if (a.size() == 0) return floatArrayOf(1f, 1f, 1f)
            val v0 = a[0].asFloat
            val v1 = if (a.size() > 1) a[1].asFloat else v0
            val v2 = if (a.size() > 2) a[2].asFloat else v0
            return floatArrayOf(v0, v1, v2)
        }
        val v = el.asFloat
        return floatArrayOf(v, v, v)
    }

    /** 解析 UV 对象；无贴图（texture 缺失）返回 null。 */
    private fun parseUv(el: JsonElement?): UvData? {
        if (el == null || !el.isJsonObject) return null
        val o = el.asJsonObject
        val texture = o.get("texture")?.let { if (it.isJsonNull) null else it.asString } ?: return null
        val mode = when (o.get("mode")?.asString) {
            "fill" -> UvData.Mode.FILL
            "animated" -> UvData.Mode.ANIMATED
            else -> UvData.Mode.STATIC
        }
        return UvData(
            texture = texture,
            mode = mode,
            texSize = parseInt2(o.get("texSize"), 16, 16),
            uvStart = parseInt2(o.get("uvStart"), 0, 0),
            uvSize = parseInt2(o.get("uvSize"), 0, 0),
            uvStep = parseInt2(o.get("uvStep"), 0, 0),
            fps = o.get("fps")?.asFloat ?: 1f,
            maxFrame = o.get("maxFrame")?.asInt ?: 1,
            loop = o.get("loop")?.asBoolean ?: true,
        )
    }

    private fun parseInt2(el: JsonElement?, dx: Int, dy: Int): IntArray {
        if (el == null || !el.isJsonArray) return intArrayOf(dx, dy)
        val a = el.asJsonArray
        return intArrayOf(
            if (a.size() > 0) a[0].asInt else dx,
            if (a.size() > 1) a[1].asInt else dy,
        )
    }

    private fun parseTrack(o: JsonObject): AnimTrack {
        val pr = o.get("pr").asString
        val ids = o.get("ids").asJsonArray.map { it.asString }
        val mode = if (o.get("m")?.asString == "op") AnimTrack.Mode.OP else AnimTrack.Mode.SET
        val keyframes = o.get("kf").asJsonArray
            .map { parseKeyframe(it.asJsonArray) }
            .sortedBy { it.tick }
        return AnimTrack(pr, ids, keyframes, mode)
    }

    private fun parseKeyframe(arr: JsonArray): AnimKeyframe {
        val tick = arr[0].asInt
        // rot 值保留原始「度」，渲染时由 ClientAnimationPlayer 统一转弧度，避免双重转换
        val value = arr[1].asDouble
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
