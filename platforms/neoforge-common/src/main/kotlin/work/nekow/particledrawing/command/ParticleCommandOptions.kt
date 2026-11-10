package work.nekow.particledrawing.command

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ColorSource
import work.nekow.particledrawing.api.CurveChannel
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.easing.EasingType

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
    private val booleanFlags = setOf("additive", "glowing", "taper", "hollow", "rainbow")
    private val allowed = setOf(
        "color", "gradient", "rainbow", "scale", "stagger", "lifetime", "axis", "taper", "hollow",
        "thickness", "style", "texture", "uv", "size", "size_world", "billboard", "spin", "spin_space",
        "additive", "glowing", "light", "intensity", "alpha_curve", "size_curve", "red_curve",
        "green_curve", "blue_curve", "light_curve", "velocity", "force", "force_ticks",
    )

    fun parse(input: String): ParticleCommandOptions {
        if (input.isBlank()) return ParticleCommandOptions()
        val values = linkedMapOf<String, String>()
        for (token in tokenize(input)) {
            val split = token.indexOf('=')
            val key = (if (split < 0) token else token.substring(0, split)).lowercase()
            val value = if (split < 0) "true" else token.substring(split + 1)
            require(key in allowed) { "未知参数 '$key'；使用 /pdraw particle help 查看参数" }
            require(split >= 0 || key in booleanFlags) { "参数 '$key' 需要写成 $key=值" }
            require(key !in values) { "参数 '$key' 写了两次" }
            values[key] = value
        }

        val colorModes = listOf("color", "gradient", "rainbow").filter(values::containsKey)
        require(colorModes.size <= 1) { "color、gradient、rainbow 只能选一个" }
        require("spin_space" !in values || "spin" in values) { "spin_space 需要和 spin 一起使用" }
        require("force_ticks" !in values || "force" in values) { "force_ticks 需要和 force 一起使用" }

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
                else -> throw IllegalArgumentException("spin_space 只能是 local 或 world")
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
            require(level in 0..15) { "light 需要是 0 到 15 的整数" }
            visual.lightLevel(level)
        }
        values["intensity"]?.let { visual.lightIntensity(float(it, "intensity")) }

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
                require(pair.size == 2) { "gradient 格式是 #RRGGBB:#RRGGBB" }
                ColorSource.gradient(parseColor(pair[0]), parseColor(pair[1]))
            }
            values.containsKey("rainbow") -> ColorSource.rainbow(
                values.getValue("rainbow").takeUnless { it.equals("true", true) }?.let {
                    val alpha = float(it, "rainbow")
                    require(alpha in 0f..1f) { "rainbow 透明度需要在 0 到 1 之间" }
                    alpha
                } ?: 1f,
            )
            values.containsKey("color") -> ColorSource.of(parseColor(values.getValue("color")))
            else -> ColorSource.of(Color.WHITE)
        }

        val lifetime = values["lifetime"]?.let { int(it, "lifetime") } ?: -1
        require(lifetime >= -1) { "lifetime 只能是 -1（无限）或非负整数" }
        val forceTicks = values["force_ticks"]?.let { int(it, "force_ticks") } ?: -1
        require(forceTicks >= -1) { "force_ticks 只能是 -1（持续）或非负整数" }
        val stagger = values["stagger"]?.let { int(it, "stagger") } ?: 0
        require(stagger >= 0) { "stagger 不能为负" }
        val thickness = values["thickness"]?.let { float(it, "thickness") } ?: 0.1f
        require(thickness > 0f) { "thickness 必须大于 0" }

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
        require(!quoted) { "参数中的双引号没有闭合" }
        require(!escaped) { "参数不能以转义符结尾" }
        if (token.isNotEmpty()) out += token.toString()
        return out
    }

    internal fun parseColor(raw: String): Color {
        val s = raw.removePrefix("#")
        require(s.length == 6 || s.length == 8) { "颜色 '$raw' 应为 #RRGGBB 或 #RRGGBBAA" }
        val value = s.toLongOrNull(16) ?: throw IllegalArgumentException("颜色 '$raw' 含非十六进制字符")
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
        else -> throw IllegalArgumentException("未知缓动 '$raw'")
    }

    private fun parseCurve(raw: String, key: String): List<CurveKey> = raw.split(',').map { item ->
        val part = item.split(':')
        require(part.size in 2..3) { "$key 的关键帧格式是 tick:value[:easing]" }
        CurveKey(float(part[0], key), float(part[1], key), part.getOrNull(2)?.let(::easing) ?: EasingType.LINEAR)
    }

    private fun parseStyle(raw: String): ParticleStyle = when (raw.lowercase()) {
        "square" -> ParticleStyle.SQUARE
        "soft_dot", "softdot", "dot" -> ParticleStyle.SOFT_DOT
        "line" -> ParticleStyle.LINE
        else -> throw IllegalArgumentException("style 只能是 square、soft_dot 或 line")
    }

    private fun parseAxis(raw: String): Draw.Axis = when (raw.lowercase()) {
        "xz" -> Draw.Axis.XZ
        "xy" -> Draw.Axis.XY
        "yz" -> Draw.Axis.YZ
        else -> throw IllegalArgumentException("axis 只能是 xz、xy 或 yz")
    }

    private fun bool(raw: String, key: String): Boolean = when (raw.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw IllegalArgumentException("$key 需要 true 或 false")
    }

    private fun float(raw: String, key: String): Float =
        raw.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$key 需要有限数字")

    private fun int(raw: String, key: String): Int =
        raw.toIntOrNull() ?: throw IllegalArgumentException("$key 需要整数")

    private fun floats(raw: String, count: Int, key: String): FloatArray {
        val p = raw.split(',')
        require(p.size == count) { "$key 需要 $count 个逗号分隔的数字" }
        return FloatArray(count) { float(p[it], key) }
    }

    private fun vector(raw: String, key: String): Vec3 {
        val n = floats(raw, 3, key)
        return Vec3(n[0].toDouble(), n[1].toDouble(), n[2].toDouble())
    }
}
