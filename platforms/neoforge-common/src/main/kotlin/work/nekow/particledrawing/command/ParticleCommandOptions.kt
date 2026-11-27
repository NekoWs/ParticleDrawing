package work.nekow.particledrawing.command

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ColorSource
import work.nekow.particledrawing.api.CurveChannel
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleSceneMaterial
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** `/pdraw particle add` 各图元共用的外观参数。 */
internal data class ParticleCommandOptions(
    val provided: Set<String> = emptySet(),
    val color: ColorSource = ColorSource.of(Color.WHITE),
    val scale: Float = 1f,
    val stagger: Int = 0,
    val visual: ParticleVisual = ParticleVisual(),
    val lifeCurve: ParticleLifeCurve? = null,
    val lifetime: Int = -1,
    val axis: Draw.Axis = Draw.Axis.XZ,
    val taper: Boolean = false,
    val hollow: Boolean = false,
    val thickness: Float = 0.1f,
    val velocity: Vec3? = null,
    val force: Vec3? = null,
    val forceTicks: Int = -1,
)

/**
 * 解析空格分隔的 `key=value` 参数。值可用双引号包住，未知键直接报错。
 */
internal object ParticleCommandOptionParser {
    private val booleanFlags = setOf("additive", "glowing", "taper", "hollow", "rainbow", "light_only")
    val appearanceKeys = setOf(
        "color", "scale", "style", "texture", "uv", "size", "size_world", "billboard", "spin",
        "spin_space", "additive", "glowing", "light", "light_only", "intensity", "alpha_curve",
        "size_curve", "red_curve", "green_curve", "blue_curve", "light_curve", "spot",
        "light_dir", "light_range", "light_falloff", "align_velocity", "stretch", "soft", "trail", "trail_sides",
        "trail_linger", "trail_width", "trail_color", "model",
        "depth_fade", "distortion",
    )
    private val allowed = appearanceKeys + setOf(
        "gradient", "rainbow", "stagger", "lifetime", "axis", "taper", "hollow", "thickness",
        "velocity", "force", "force_ticks",
    )

    fun parse(input: String): ParticleCommandOptions {
        if (input.isBlank()) return ParticleCommandOptions()
        val values = linkedMapOf<String, String>()
        for (token in tokenize(input)) {
            val split = token.indexOf('=')
            val key = (if (split < 0) token else token.substring(0, split)).lowercase()
            val value = if (split < 0) "true" else token.substring(split + 1)
            requireLocalized(key in allowed) { translation("error.command.unknown_option", key) }
            requireLocalized(split >= 0 || key in booleanFlags) { translation("error.command.missing_value", key) }
            requireLocalized(key !in values) { translation("error.command.duplicate_option", key) }
            values[key] = value
        }

        val colorModes = listOf("color", "gradient", "rainbow").filter(values::containsKey)
        requireLocalized(colorModes.size <= 1) { translation("error.command.color_mode") }
        requireLocalized("spin_space" !in values || "spin" in values) { translation("error.command.spin_space") }
        requireLocalized("force_ticks" !in values || "force" in values) { translation("error.command.force_ticks") }

        val visual = ParticleVisual()
        values["style"]?.let { visual.style(parseStyle(it)) }
        values["texture"]?.let { visual.texture(it) }
        values["uv"]?.let {
            val n = floats(it, 4, "uv")
            visual.uv(n[0], n[1], n[2], n[3])
        }
        values["size"]?.let {
            val n = floats(it, 2, "size")
            visual.aniso(n[0], n[1])
        }
        values["size_world"]?.let {
            val n = floats(it, 2, "size_world")
            visual.anisoWorld(n[0], n[1])
        }
        values["billboard"]?.let { visual.billboard(bool(it, "billboard")) }
        val spinLocal = values["spin_space"]?.let {
            when (it.lowercase()) {
                "local" -> true
                "world" -> false
                else -> throw LocalizedArgumentException(translation("error.command.spin_space_choice"))
            }
        } ?: true
        values["spin"]?.let {
            val n = floats(it, 3, "spin")
            visual.spin(Math.toRadians(n[0].toDouble()), Math.toRadians(n[1].toDouble()), Math.toRadians(n[2].toDouble()), spinLocal)
        }
        values["additive"]?.let { visual.additive(bool(it, "additive")) }
        values["glowing"]?.let { visual.glowing(bool(it, "glowing")) }
        values["light"]?.let {
            val level = int(it, "light")
            requireLocalized(level in 0..15) { translation("error.command.light_level") }
            visual.lightLevel(level)
        }
        values["intensity"]?.let { visual.lightIntensity(float(it, "intensity")) }

        // 光源形状：spot=外锥[,内锥[,锐度]]，light_dir=偏航,俯仰，light_range=半径，light_falloff=曲线名
        val spot = values["spot"]?.let { optionalFloats(it, 3, "spot") }
        val range = values["light_range"]?.let { float(it, "light_range") } ?: 0f
        requireLocalized(range >= 0f) { translation("error.command.light_range") }
        if (spot != null) {
            val outer = spot[0].toDouble()
            val inner = spot.getOrElse(1) { 0f }.toDouble()
            val edge = spot.getOrElse(2) { 1f }.toDouble()
            visual.spotlight(range.toDouble(), outer, inner, edge)
        } else if (range > 0f) {
            visual.lightFalloff(LightFalloff.LINEAR, range.toDouble())
        }
        values["light_dir"]?.let {
            val n = floats(it, 2, "light_dir")
            visual.lightDirection(n[0].toDouble(), n[1].toDouble())
        }
        values["light_falloff"]?.let {
            val curve = LightFalloff.byName(it)
                ?: throw LocalizedArgumentException(translation("error.command.light_falloff", it))
            visual.lightFalloff(curve, range.toDouble())
        }
        values["light_only"]?.let { visual.lightOnly(bool(it, "light_only")) }
        values["soft"]?.let { visual.softFade = float(it, "soft").toDouble() }
        if ("depth_fade" in values || "distortion" in values) {
            visual.sceneMaterial = ParticleSceneMaterial(
                values["depth_fade"]?.let { float(it, "depth_fade") } ?: 0f,
                values["distortion"]?.let { float(it, "distortion") } ?: 0f,
            )
        }
        values["trail"]?.let { visual.trailTicks = int(it, "trail") }
        values["trail_sides"]?.let { visual.trailSides = int(it, "trail_sides") }
        val trailWidth = values["trail_width"]?.let { floats(it, 2, "trail_width") }
        val trailColors = values["trail_color"]?.let {
            val pair = it.split(':')
            requireLocalized(pair.size == 2) { translation("error.command.trail_color") }
            pair.map { color -> color.takeUnless { value -> value.equals("inherit", ignoreCase = true) }?.let(::parseColor) }
        }
        if (trailWidth != null || trailColors != null || "trail_linger" in values) {
            visual.trailStyle = work.nekow.particledrawing.api.ParticleTrailStyle(
                values["trail_linger"]?.let { int(it, "trail_linger") } ?: 0,
                trailWidth?.get(0) ?: 0f, trailWidth?.get(1) ?: 1f, trailColors?.get(0), trailColors?.get(1),
            )
        }
        values["model"]?.let { if (it != "none") visual.model = ResourceId.parse(it) }
        values["stretch"]?.let { visual.stretch = float(it, "stretch").toDouble() }
        visual.alignVelocity = values["align_velocity"]?.let { bool(it, "align_velocity") }
            ?: ("stretch" in values && visual.stretch > 0.0)

        var curves = ParticleLifeCurve.EMPTY
        fun curve(key: String, channel: CurveChannel) {
            values[key]?.let { curves = curves.plus(ParticleCurve(channel, parseCurve(it, key))) }
        }
        curve("alpha_curve", CurveChannel.ALPHA)
        curve("size_curve", CurveChannel.SCALE)
        curve("red_curve", CurveChannel.RED)
        curve("green_curve", CurveChannel.GREEN)
        curve("blue_curve", CurveChannel.BLUE)
        curve("light_curve", CurveChannel.LIGHT)

        val color = when {
            values.containsKey("gradient") -> {
                val pair = values.getValue("gradient").split(':')
                requireLocalized(pair.size == 2) { translation("error.command.gradient") }
                ColorSource.gradient(parseColor(pair[0]), parseColor(pair[1]))
            }
            values.containsKey("rainbow") -> ColorSource.rainbow(
                values.getValue("rainbow").takeUnless { it.equals("true", true) }?.let {
                    val alpha = float(it, "rainbow")
                    requireLocalized(alpha in 0f..1f) { translation("error.command.rainbow_alpha") }
                    alpha
                } ?: 1f,
            )
            values.containsKey("color") -> ColorSource.of(parseColor(values.getValue("color")))
            else -> ColorSource.of(Color.WHITE)
        }

        val lifetime = values["lifetime"]?.let { int(it, "lifetime") } ?: -1
        requireLocalized(lifetime >= -1) { translation("error.command.lifetime") }
        val forceTicks = values["force_ticks"]?.let { int(it, "force_ticks") } ?: -1
        requireLocalized(forceTicks >= -1) { translation("error.command.force_duration") }
        val stagger = values["stagger"]?.let { int(it, "stagger") } ?: 0
        requireLocalized(stagger >= 0) { translation("error.stagger") }
        val thickness = values["thickness"]?.let { float(it, "thickness") } ?: 0.1f
        requireLocalized(thickness > 0f) { translation("error.command.thickness") }

        return ParticleCommandOptions(
            provided = values.keys,
            color = color,
            scale = values["scale"]?.let { float(it, "scale") } ?: 1f,
            stagger = stagger,
            visual = visual,
            lifeCurve = curves.takeUnless { it.isEmpty() },
            lifetime = lifetime,
            axis = values["axis"]?.let { parseAxis(it) } ?: Draw.Axis.XZ,
            taper = values["taper"]?.let { bool(it, "taper") } ?: false,
            hollow = values["hollow"]?.let { bool(it, "hollow") } ?: false,
            thickness = thickness,
            velocity = values["velocity"]?.let { vector(it, "velocity") },
            force = values["force"]?.let { vector(it, "force") },
            forceTicks = forceTicks,
        )
    }

    internal fun tokenize(input: String): List<String> {
        val out = ArrayList<String>()
        val token = StringBuilder()
        var quoted = false
        var escaped = false
        for (c in input) {
            when {
                escaped -> { token.append(c); escaped = false }
                c == '\\' && quoted -> escaped = true
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> if (token.isNotEmpty()) {
                    out += token.toString()
                    token.setLength(0)
                }
                else -> token.append(c)
            }
        }
        requireLocalized(!quoted) { translation("error.command.unclosed_quote") }
        requireLocalized(!escaped) { translation("error.command.trailing_escape") }
        if (token.isNotEmpty()) out += token.toString()
        return out
    }

    /** CSS 颜色名到 #RRGGBB 的映射；解析时和十六进制颜色走同一条路径。 */
    private val namedColors: Map<String, String> = mapOf(
        "black" to "#000000", "silver" to "#C0C0C0", "gray" to "#808080", "grey" to "#808080",
        "white" to "#FFFFFF", "maroon" to "#800000", "red" to "#FF0000", "purple" to "#800080",
        "fuchsia" to "#FF00FF", "magenta" to "#FF00FF", "green" to "#008000", "lime" to "#00FF00",
        "olive" to "#808000", "yellow" to "#FFFF00", "navy" to "#000080", "blue" to "#0000FF",
        "teal" to "#008080", "aqua" to "#00FFFF", "cyan" to "#00FFFF", "orange" to "#FFA500",
        "gold" to "#FFD700", "pink" to "#FFC0CB", "hotpink" to "#FF69B4", "deeppink" to "#FF1493",
        "violet" to "#EE82EE", "indigo" to "#4B0082", "brown" to "#A52A2A", "chocolate" to "#D2691E",
        "coral" to "#FF7F50", "crimson" to "#DC143C", "darkred" to "#8B0000", "darkgreen" to "#006400",
        "darkblue" to "#00008B", "darkcyan" to "#008B8B", "darkorange" to "#FF8C00", "darkviolet" to "#9400D3",
        "lightblue" to "#ADD8E6", "lightgreen" to "#90EE90", "lightgray" to "#D3D3D3", "lightgrey" to "#D3D3D3",
        "skyblue" to "#87CEEB", "royalblue" to "#4169E1", "tomato" to "#FF6347", "turquoise" to "#40E0D0",
        "tan" to "#D2B48C", "beige" to "#F5F5DC", "ivory" to "#FFFFF0", "khaki" to "#F0E68C",
        "lavender" to "#E6E6FA", "plum" to "#DDA0DD", "orchid" to "#DA70D6", "salmon" to "#FA8072",
        "sienna" to "#A0522D", "slategray" to "#708090", "slategrey" to "#708090", "springgreen" to "#00FF7F",
        "seagreen" to "#2E8B57", "forestgreen" to "#228B22", "midnightblue" to "#191970", "dodgerblue" to "#1E90FF",
        "steelblue" to "#4682B4", "firebrick" to "#B22222", "rebeccapurple" to "#663399", "peru" to "#CD853F",
        "wheat" to "#F5DEB3", "azure" to "#F0FFFF", "honeydew" to "#F0FFF0", "lavenderblush" to "#FFF0F5",
        "antiquewhite" to "#FAEBD7", "bisque" to "#FFE4C4", "burlywood" to "#DEB887", "cadetblue" to "#5F9EA0",
        "chartreuse" to "#7FFF00", "cornflowerblue" to "#6495ED", "cornsilk" to "#FFF8DC", "darkgoldenrod" to "#B8860B",
        "darkkhaki" to "#BDB76B", "darkmagenta" to "#8B008B", "darkolivegreen" to "#556B2F", "darkorchid" to "#9932CC",
        "darksalmon" to "#E9967A", "darkseagreen" to "#8FBC8F", "darkslateblue" to "#483D8B", "darkslategray" to "#2F4F4F",
        "darkturquoise" to "#00CED1", "deepskyblue" to "#00BFFF", "dimgray" to "#696969", "gainsboro" to "#DCDCDC",
        "greenyellow" to "#ADFF2F", "indianred" to "#CD5C5C", "lawngreen" to "#7CFC00", "lightcoral" to "#F08080",
        "lightcyan" to "#E0FFFF", "lightpink" to "#FFB6C1", "lightsalmon" to "#FFA07A", "lightseagreen" to "#20B2AA",
        "lightskyblue" to "#87CEFA", "lightslategray" to "#778899", "lightsteelblue" to "#B0C4DE", "lightyellow" to "#FFFFE0",
        "limegreen" to "#32CD32", "mediumaquamarine" to "#66CDAA", "mediumblue" to "#0000CD", "mediumorchid" to "#BA55D3",
        "mediumpurple" to "#9370DB", "mediumseagreen" to "#3CB371", "mediumslateblue" to "#7B68EE", "mediumspringgreen" to "#00FA9A",
        "mediumturquoise" to "#48D1CC", "mediumvioletred" to "#C71585", "navajowhite" to "#FFDEAD", "olivedrab" to "#6B8E23",
        "orangered" to "#FF4500", "palegoldenrod" to "#EEE8AA", "palegreen" to "#98FB98", "paleturquoise" to "#AFEEEE",
        "palevioletred" to "#DB7093", "papayawhip" to "#FFEFD5", "peachpuff" to "#FFDAB9", "powderblue" to "#B0E0E6",
        "rosybrown" to "#BC8F8F", "saddlebrown" to "#8B4513", "sandybrown" to "#F4A460", "slateblue" to "#6A5ACD",
        "snow" to "#FFFAFA", "thistle" to "#D8BFD8", "whitesmoke" to "#F5F5F5", "yellowgreen" to "#9ACD32",
    )

    /** 补全与错误提示共用的颜色名列表。 */
    val colorNames: List<String> = namedColors.keys.sorted()

    internal fun parseColor(raw: String): Color {
        val s = namedColors[raw.trim().lowercase()]?.removePrefix("#") ?: raw.removePrefix("#")
        requireLocalized(s.length == 6 || s.length == 8) { translation("error.command.color_format", raw) }
        val value = s.toLongOrNull(16) ?: throw LocalizedArgumentException(translation("error.command.color_digits", raw))
        val r: Int
        val g: Int
        val b: Int
        val a: Int
        if (s.length == 6) {
            r = ((value shr 16) and 255).toInt(); g = ((value shr 8) and 255).toInt(); b = (value and 255).toInt(); a = 255
        } else {
            r = ((value shr 24) and 255).toInt(); g = ((value shr 16) and 255).toInt(); b = ((value shr 8) and 255).toInt(); a = (value and 255).toInt()
        }
        return Color.ofInt(r, g, b, a)
    }

    internal val easingNames = EasingType.PRESETS.map { checkNotNull(it.name).lowercase() } + "none"

    internal fun easing(raw: String): EasingType = when (raw.lowercase().replace('-', '_')) {
        "none", "step" -> EasingType.NONE
        "linear" -> EasingType.LINEAR
        "ease_in" -> EasingType.EASE_IN
        "ease_out" -> EasingType.EASE_OUT
        "ease_in_out" -> EasingType.EASE_IN_OUT
        "ease_in_quad" -> EasingType.EASE_IN_QUAD
        "ease_out_quad" -> EasingType.EASE_OUT_QUAD
        "ease_in_out_quad" -> EasingType.EASE_IN_OUT_QUAD
        "ease_in_cubic" -> EasingType.EASE_IN_CUBIC
        "ease_out_cubic" -> EasingType.EASE_OUT_CUBIC
        "ease_in_out_cubic" -> EasingType.EASE_IN_OUT_CUBIC
        "ease_in_bounce" -> EasingType.EASE_IN_BOUNCE
        "ease_out_bounce" -> EasingType.EASE_OUT_BOUNCE
        "ease_in_elastic" -> EasingType.EASE_IN_ELASTIC
        "ease_out_elastic" -> EasingType.EASE_OUT_ELASTIC
        else -> throw LocalizedArgumentException(translation("error.easing.unknown", raw))
    }

    private fun parseCurve(raw: String, key: String): List<CurveKey> = raw.split(',').map { item ->
        val part = item.split(':')
        requireLocalized(part.size in 2..3) { translation("error.command.curve_format", key) }
        CurveKey(float(part[0], key), float(part[1], key), part.getOrNull(2)?.let(::easing) ?: EasingType.LINEAR)
    }

    private fun parseStyle(raw: String): ParticleStyle = when (raw.lowercase()) {
        "square" -> ParticleStyle.SQUARE
        "soft_dot", "softdot", "dot" -> ParticleStyle.SOFT_DOT
        "line" -> ParticleStyle.LINE
        else -> throw LocalizedArgumentException(translation("error.command.style"))
    }

    private fun parseAxis(raw: String): Draw.Axis = when (raw.lowercase()) {
        "xz" -> Draw.Axis.XZ
        "xy" -> Draw.Axis.XY
        "yz" -> Draw.Axis.YZ
        else -> throw LocalizedArgumentException(translation("error.command.axis"))
    }

    private fun bool(raw: String, key: String): Boolean = when (raw.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw LocalizedArgumentException(translation("error.command.boolean", key))
    }

    private fun float(raw: String, key: String): Float =
        raw.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw LocalizedArgumentException(translation("error.command.finite", key))

    private fun int(raw: String, key: String): Int =
        raw.toIntOrNull() ?: throw LocalizedArgumentException(translation("error.command.integer", key))

    /** 允许省略尾部数字：`spot=30`、`spot=30,15`、`spot=30,15,2` 都成立。 */
    private fun optionalFloats(raw: String, max: Int, key: String): FloatArray {
        val p = raw.split(',')
        requireLocalized(p.size in 1..max) { translation("error.command.max_components", key, max) }
        return FloatArray(p.size) { float(p[it], key) }
    }

    private fun floats(raw: String, count: Int, key: String): FloatArray {
        val p = raw.split(',')
        requireLocalized(p.size == count) { translation("error.command.exact_components", key, count) }
        return FloatArray(count) { float(p[it], key) }
    }

    private fun vector(raw: String, key: String): Vec3 {
        val n = floats(raw, 3, key)
        return Vec3(n[0].toDouble(), n[1].toDouble(), n[2].toDouble())
    }
}
