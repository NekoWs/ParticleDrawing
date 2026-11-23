package work.nekow.particledrawing.command.batch

import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import net.minecraft.world.phys.Vec3

/**
 * `/pdraw particle batch` 的键注册表。
 *
 * 键表、Tab 补全与 `help` 共用这一张表；值语法只用于回显，真正解析在各 kind 的落地器里。
 */
internal enum class BatchKind {
    EMITTER, SHAPE, PROGRAM;

    companion object {
        /** 形状名放在这里，kind 判断时先查它。 */
        val SHAPES = listOf(
            "dot", "field", "line", "beam", "helix", "circle", "disc", "triangle", "hexagram", "rect", "sphere", "cuboid",
        )

        fun of(raw: String): BatchKind? = when (raw.lowercase()) {
            "emitter" -> EMITTER
            "program" -> PROGRAM
            in SHAPES -> SHAPE
            else -> null
        }
    }
}

/** 一条 `key=value` 选项；`value` 保留引号，交给值解析决定怎么去掉。 */
internal data class BatchToken(val key: String, val value: String)

internal data class BatchKey(
    /** 完整键名；动态前缀用 `<字段>` 表示。 */
    val name: String,
    val kinds: Set<BatchKind>,
    val group: String,
    /** 值语法，供补全与 help 使用。 */
    val syntax: String,
    val repeatable: Boolean = false,
    /** 枚举键的取值，补全与校验共用。 */
    val choices: List<String>? = null,
)

internal object BatchKeys {
    private val ALL = BatchKind.values().toSet()
    private val EMITTER = setOf(BatchKind.EMITTER)
    private val SHAPE = setOf(BatchKind.SHAPE)
    private val PROGRAM = setOf(BatchKind.PROGRAM)

    private fun key(
        name: String, kinds: Set<BatchKind>, group: String, syntax: String,
        repeatable: Boolean = false, choices: List<String>? = null,
    ) = BatchKey(name, kinds, group, syntax, repeatable, choices)

    private val TOP = listOf(
        key("kind", ALL, "顶层", "emitter|形状|program"),
        key("keep", setOf(BatchKind.EMITTER, BatchKind.PROGRAM), "顶层", "true|false", choices = listOf("true", "false")),
        key("mode", EMITTER, "顶层", "start|burst|config", choices = listOf("start", "burst", "config")),
        key("dryrun", ALL, "顶层", "true|false", choices = listOf("true", "false")),
    )

    private val ANCHOR = listOf(
        key("anchor", EMITTER, "锚点", "fixed|entity|emitter", choices = listOf("fixed", "entity", "emitter")),
        key("anchor.pos", EMITTER, "锚点", "x,y,z"),
        key("anchor.entity", EMITTER, "锚点", "选择器"),
        key("anchor.offset", EMITTER, "锚点", "x,y,z"),
        key("anchor.local", EMITTER, "锚点", "true|false", choices = listOf("true", "false")),
        key("anchor.emitter", EMITTER, "锚点", "配置名"),
    )

    private val SAMPLING = listOf(
        key("sample.count", EMITTER, "采样", "1..8192"),
        key("sample.range", EMITTER, "采样", "begin,end,step"),
        key("sample.grid", EMITTER, "采样", "无值"),
        key("sample.bounds", EMITTER, "采样", "x1,y1,z1,x2,y2,z2"),
        key("sample.step", EMITTER, "采样", "小数"),
        key("sample.image", EMITTER, "采样", "文件名"),
        key("sample.mesh", EMITTER, "采样", "文件名"),
        key("sample.mesh_count", EMITTER, "采样", "1..8192"),
        key("sample.mesh_seed", EMITTER, "采样", "整数"),
        key("sample.stride", EMITTER, "采样", "1..256"),
        key("sample.plane", EMITTER, "采样", "xy|xz|yz", choices = listOf("xy", "xz", "yz")),
        key("sample.spacing", EMITTER, "采样", "小数"),
        key("sample.alpha", EMITTER, "采样", "0..1"),
    )

    private val FORMULA = listOf(
        key("let.<名字>", EMITTER, "公式", "双引号公式", repeatable = true),
        key("birth.<属性>", EMITTER, "公式", "双引号公式", repeatable = true),
        key("motion.<属性>", EMITTER, "公式", "双引号公式", repeatable = true),
        key("where", EMITTER, "公式", "双引号条件"),
        key("kill", EMITTER, "公式", "双引号条件"),
        key("select", EMITTER, "公式", "双引号条件"),
        key("orbit", EMITTER, "公式", "双引号 vec3 公式"),
    )

    private val EMITTER_ONLY = listOf(
        key("variable.<名字>", EMITTER, "运行期数值", "小数", repeatable = true),
        key("state.<名字>", EMITTER, "运行期数值", "小数", repeatable = true),
        key("emitter.lifetime", EMITTER, "发射器", "-1 或刻数"),
        key("emitter.emit_from", EMITTER, "发射器", "0..72000"),
        key("emitter.emit_until", EMITTER, "发射器", "-1 或刻数"),
        key("rate", EMITTER, "发射器", "1..1024"),
        key("interval", EMITTER, "发射器", "1..72000"),
        key("max_alive", EMITTER, "发射器", "1..8192"),
        key("spacing", EMITTER, "发射器", "0 或 0.05..64"),
        key("simulation_range", EMITTER, "发射器", "0..512"),
        key("simulation_range.preserve", EMITTER, "发射器", "true|false", choices = listOf("true", "false")),
        key("follow", EMITTER, "发射器", "true|false", choices = listOf("true", "false")),
        key("collide", EMITTER, "碰撞", "none|solid|liquid|both|entity|all", choices = listOf("none", "solid", "liquid", "both", "entity", "all")),
        key("collision.size", EMITTER, "碰撞", "0..1"),
        key("collision.steps", EMITTER, "碰撞", "1..4"),
        key("collision.bounce", EMITTER, "碰撞", "0..1"),
        key("collision.friction", EMITTER, "碰撞", "0..1"),
        key("subemit", EMITTER, "子发射", "on|off", choices = listOf("on", "off")),
        key("subemit.count", EMITTER, "子发射", "1..64"),
        key("subemit.lifetime", EMITTER, "子发射", "1..72000"),
        key("subemit.speed", EMITTER, "子发射", "0..2"),
        key("subemit.scale", EMITTER, "子发射", "0.05..4"),
        key("unset", EMITTER, "清除", "段名或段.字段", repeatable = true),
    )

    private val SHAPE_PARAMS = listOf(
        key("pos", SHAPE, "形状", "x,y,z"),
        key("start", SHAPE, "形状", "x,y,z"),
        key("end", SHAPE, "形状", "x,y,z"),
        key("center", SHAPE, "形状", "x,y,z"),
        key("radius", SHAPE, "形状", "小数"),
        key("height", SHAPE, "形状", "小数"),
        key("turns", SHAPE, "形状", "小数"),
        key("count", SHAPE, "形状", "整数"),
        key("perimeter", SHAPE, "形状", "整数"),
        key("layers", SHAPE, "形状", "整数"),
        key("segments", SHAPE, "形状", "整数"),
        key("density", SHAPE, "形状", "整数"),
        key("depth", SHAPE, "形状", "小数"),
        key("width", SHAPE, "形状", "小数"),
    )

    private val PARTICLE = listOf(
        key("particle.lifetime", SHAPE, "一次性粒子", "-1 或刻数"),
        key("particle.velocity", SHAPE, "一次性粒子", "x,y,z"),
        key("particle.force", SHAPE, "一次性粒子", "x,y,z"),
        key("particle.force_ticks", SHAPE, "一次性粒子", "-1 或刻数"),
        key("particle.stagger", SHAPE, "一次性粒子", "刻数"),
        key("shape.axis", SHAPE, "一次性粒子", "xz|xy|yz", choices = listOf("xz", "xy", "yz")),
        key("shape.hollow", SHAPE, "一次性粒子", "true|false", choices = listOf("true", "false")),
        key("shape.taper", SHAPE, "一次性粒子", "true|false", choices = listOf("true", "false")),
        key("shape.thickness", SHAPE, "一次性粒子", "小数"),
    )

    private val PROGRAM_KEYS = listOf(
        key("program.<序号>", PROGRAM, "组编排", "动作,参数...", repeatable = true),
        key("morph.slots", PROGRAM, "形变", "数量,双引号位置公式"),
        key("morph.yaw", PROGRAM, "形变", "双引号公式"),
        key("morph.pitch", PROGRAM, "形变", "双引号公式"),
        key("morph.size", PROGRAM, "形变", "双引号公式"),
        key("morph.control1", PROGRAM, "形变", "双引号公式"),
        key("morph.control2", PROGRAM, "形变", "双引号公式"),
        key("morph.ticks", PROGRAM, "形变", "刻数"),
        key("morph.stagger", PROGRAM, "形变", "刻数"),
        key("morph.loop", PROGRAM, "形变", "整数"),
        key("morph.max_speed", PROGRAM, "形变", "小数"),
        key("morph.max_accel", PROGRAM, "形变", "小数"),
        key("morph.converge", PROGRAM, "形变", "小数"),
        key("morph.path", PROGRAM, "形变", "straight|quadratic|cubic", choices = listOf("straight", "quadratic", "cubic")),
        key("morph.easing", PROGRAM, "形变", "缓动名"),
        key("morph.wobble", PROGRAM, "形变", "none|bell|noise|vortex[,强度[,峰数]]", choices = listOf("none", "bell", "noise", "vortex")),
        key("morph.allocation", PROGRAM, "形变", "nearest|index|angle", choices = listOf("nearest", "index", "angle")),
        key("morph.dynamic", PROGRAM, "形变", "true|false", choices = listOf("true", "false")),
        key("morph.keep_velocity", PROGRAM, "形变", "true|false", choices = listOf("true", "false")),
        key("morph.attributes", PROGRAM, "形变", "true|false", choices = listOf("true", "false")),
    )

    private val APPEARANCE = ParticleCommandOptionParser.appearanceKeys.sorted().map {
        key("appearance.$it", setOf(BatchKind.EMITTER, BatchKind.SHAPE), "外观", "值")
    }

    val all: List<BatchKey> = TOP + ANCHOR + SAMPLING + FORMULA + EMITTER_ONLY + SHAPE_PARAMS + PARTICLE + PROGRAM_KEYS + APPEARANCE

    val groups: List<String> = all.map(BatchKey::group).distinct()

    fun keysFor(kind: BatchKind): List<BatchKey> = all.filter { kind in it.kinds }

    fun exact(name: String): BatchKey? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** 动态前缀键（`let.<名字>`、`program.<序号>` 这类）属于哪种 kind。 */
    fun dynamic(name: String, kind: BatchKind): BatchKey? {
        if ('.' !in name) return null
        val prefix = name.substringBefore('.')
        return when (prefix) {
            "let", "birth", "motion" ->
                if (kind == BatchKind.EMITTER) BatchKey("$prefix.<名字>", setOf(kind), "公式", "双引号公式", repeatable = true) else null
            "variable", "state" ->
                if (kind == BatchKind.EMITTER) BatchKey("$prefix.<名字>", setOf(kind), "运行期数值", "小数", repeatable = true) else null
            "program" ->
                if (kind == BatchKind.PROGRAM && name.removePrefix("program.").toIntOrNull() != null) {
                    BatchKey("program.<序号>", setOf(kind), "组编排", "动作,参数...", repeatable = true)
                } else null
            else -> null
        }
    }

    /** 校验阶段把具体键映射回注册表条目；未知键直接报错。 */
    fun require(name: String, kind: BatchKind): BatchKey {
        exact(name)?.takeIf { kind in it.kinds }?.let { return it }
        dynamic(name, kind)?.let { return it }
        throw LocalizedArgumentException(translation("error.batch.unknown_key", name, kindLabel(kind)))
    }

    fun kindLabel(kind: BatchKind): String = when (kind) {
        BatchKind.EMITTER -> "emitter"
        BatchKind.SHAPE -> "shape"
        BatchKind.PROGRAM -> "program"
    }

    fun shapeRequired(shape: String): List<String> = when (shape) {
        "dot" -> listOf("pos")
        "field" -> listOf("count", "pos")
        "line" -> listOf("start", "end", "count")
        "beam" -> listOf("start", "end")
        "helix" -> listOf("center", "radius", "height", "turns", "count")
        "circle" -> listOf("center", "radius", "count")
        "disc" -> listOf("center", "radius", "perimeter", "layers")
        "triangle", "hexagram" -> listOf("center", "radius", "segments")
        "rect" -> listOf("center", "width", "height", "density")
        "sphere" -> listOf("center", "radius", "count")
        "cuboid" -> listOf("center", "width", "height", "depth", "density")
        else -> emptyList()
    }

    /** 形变动作的补全值。 */
    val morphKeys: List<BatchKey> = PROGRAM_KEYS.filter { it.name.startsWith("morph.") }

    val easingNames: List<String> = listOf(
        "linear", "ease_in", "ease_out", "ease_in_out", "ease_in_quad", "ease_out_quad", "ease_in_out_quad",
        "ease_in_cubic", "ease_out_cubic", "ease_in_out_cubic", "ease_in_bounce", "ease_out_bounce",
        "ease_in_elastic", "ease_out_elastic", "none",
    )

    /** 动作名到参数说明。补全与错误提示共用。 */
    val actionArgs: Map<String, String> = linkedMapOf(        "delay" to "ticks",
        "move" to "x,y,z,ticks[,easing]",
        "move_offset" to "ratio,ticks[,easing]",
        "move_path" to "ticks,points[,easing]",
        "rotate" to "x,y,z,degrees,ticks[,easing]",
        "recolor" to "color,ticks[,easing]",
        "scale_by" to "ratio,ticks[,easing]",
        "scale_to" to "ratio,ticks[,easing]",
        "fade_in" to "ticks[,easing]",
        "fade_out" to "ticks[,easing]",
        "fade_out_keep" to "ticks[,easing]",
        "spin" to "x,y,z,degrees_per_tick",
        "pulse" to "peak,half_period[,cycles]",
        "pivot" to "x,y,z",
        "follow" to "entity[,x,y,z[,local]]",
        "anchor.fixed" to "x,y,z",
        "anchor.entity" to "entity[,x,y,z[,local]]",
        "variable.set" to "name,value",
        "variable.live" to "name,formula",
        "variable.ease" to "name,value,ticks[,easing]",
        "expression" to "ticks,code",
        "expression.local" to "ticks,code",
        "stop_continuous" to "",
        "destroy_after" to "ticks",
        "retire" to "[grace]",
        "entity" to "handle,entity",
        "morph.start" to "",
        "morph.reset" to "",
    )

    /** 动作名列表，顺序与参数表一致。 */
    val actionNames: List<String> = actionArgs.keys.toList()
}

/** 位置分量：绝对、相对命令源（`~`）、或命令源的局部坐标（`^`）。 */
internal sealed interface BatchCoord {
    data class Absolute(val value: Double) : BatchCoord
    data class Relative(val value: Double) : BatchCoord
    data class Local(val value: Double) : BatchCoord
}

/**
 * `key=value` 分词与值解析。
 *
 * 分词只认「前面是空白、形如 Key=」的位置作为新键，因此位置值可以直接写 `~ ~4 ~`，
 * 不必为了空格再包一层引号；引号内的 `=` 与空白都不算边界。
 */
internal object BatchOptions {

    private val KEY_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.]*")

    fun parse(raw: String): List<BatchToken> {
        if (raw.isBlank()) return emptyList()
        val starts = ArrayList<Int>()
        var quoted = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && quoted -> i += 2
                c == '"' -> { quoted = !quoted; i++ }
                quoted -> i++
                i == 0 || raw[i - 1].isWhitespace() -> {
                    val match = KEY_PATTERN.matchAt(raw, i)
                    if (match != null && raw.getOrNull(match.range.last + 1) == '=') {
                        starts += i
                        i = match.range.last + 2
                    } else {
                        if (starts.isEmpty()) throw LocalizedArgumentException(translation("error.batch.syntax", raw))
                        i++
                    }
                }
                else -> {
                    if (starts.isEmpty()) throw LocalizedArgumentException(translation("error.batch.syntax", raw))
                    i++
                }
            }
        }
        requireLocalized(!quoted) { translation("error.command.unclosed_quote") }
        requireLocalized(starts.isNotEmpty()) { translation("error.batch.syntax", raw) }
        return starts.mapIndexed { index, start ->
            val end = starts.getOrElse(index + 1) { raw.length }
            token(raw.substring(start, end).trim())
        }
    }

    private fun token(raw: String): BatchToken {
        val split = raw.indexOf('=')
        requireLocalized(split > 0) { translation("error.batch.syntax", raw) }
        val rawKey = raw.substring(0, split)
        val dot = rawKey.indexOf('.')
        val key = if (dot < 0) rawKey.lowercase() else rawKey.substring(0, dot).lowercase() + rawKey.substring(dot)
        return BatchToken(key, raw.substring(split + 1).trim())
    }

    /** 去掉包裹的引号并还原转义；没被整段引号包住时原样返回。 */
    fun unquote(raw: String): String {
        val text = raw.trim()
        if (text.length < 2 || text.first() != '"' || text.last() != '"') return text
        val body = text.substring(1, text.length - 1)
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) { out.append(body[i + 1]); i += 2 } else { out.append(c); i++ }
        }
        return out.toString()
    }

    /** 顶层逗号切分，引号内的逗号不切。 */
    fun splitList(raw: String): List<String> {
        val out = ArrayList<String>()
        var quoted = false
        var escaped = false
        val current = StringBuilder()
        for (c in raw) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' && quoted -> { current.append(c); escaped = true }
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == ',' && !quoted -> { out += current.toString().trim(); current.setLength(0) }
                else -> current.append(c)
            }
        }
        requireLocalized(!quoted) { translation("error.command.unclosed_quote") }
        out += current.toString().trim()
        return out
    }

    /** 去掉引号后的文本值。 */
    fun text(raw: String): String = unquote(raw).trim()

    /** 公式/代码值：去掉引号后不能为空。 */
    fun formula(raw: String): String {
        val text = text(raw)
        requireLocalized(text.isNotEmpty()) { translation("error.batch.empty_value") }
        return text
    }

    fun value(token: BatchToken, choices: List<String>): String = choice(token.value, token.key, choices)

    fun requireCount(values: List<String>, count: Int, key: String) {
        requireLocalized(values.size == count) { translation("error.batch.value_count", key, count) }
    }

    fun requireCountAtLeast(values: List<String>, count: Int, key: String) {
        requireLocalized(values.size >= count) { translation("error.batch.value_count_min", key, count) }
    }

    fun double(raw: String, key: String): Double {
        val text = unquote(raw)
        val value = text.toDoubleOrNull()
        requireLocalized(value != null && value.isFinite()) { translation("error.batch.value_number", key, raw) }
        return value!!
    }

    fun int(raw: String, key: String): Int {
        val text = unquote(raw)
        return text.toIntOrNull() ?: throw LocalizedArgumentException(translation("error.batch.value_integer", key, raw))
    }

    fun bool(raw: String, key: String): Boolean = when (unquote(raw).lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw LocalizedArgumentException(translation("error.command.boolean", key))
    }

    fun choice(raw: String, key: String, choices: List<String>): String {
        val text = unquote(raw).lowercase()
        requireLocalized(text in choices) { translation("error.batch.value_choice", key, choices.joinToString("|")) }
        return text
    }

    fun easing(raw: String, key: String): EasingType {
        val text = unquote(raw).lowercase().replace('-', '_')
        return when (text) {
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
    }

    /** 位置三分量；允许 `~`/`^` 前缀与空格或逗号分隔。 */
    fun position(raw: String, key: String): List<BatchCoord> {
        val text = unquote(raw)
        val parts = text.split(',', ' ', '\t').filter(String::isNotBlank)
        requireLocalized(parts.size == 3) { translation("error.batch.value_position", key) }
        return parts.map { part -> coord(part, key) }
    }

    fun offset(raw: String, key: String): Vec3 {
        val text = unquote(raw)
        val parts = text.split(',', ' ', '\t').filter(String::isNotBlank)
        requireCount(parts, 3, key)
        return Vec3(double(parts[0], key), double(parts[1], key), double(parts[2], key))
    }

    private fun coord(part: String, key: String): BatchCoord {
        val prefix = when (part.firstOrNull()) {
            '~' -> '~'
            '^' -> '^'
            else -> ' '
        }
        val number = if (prefix == ' ') part else part.substring(1)
        val value = if (number.isEmpty()) 0.0 else double(number, key)
        return when (prefix) {
            '~' -> BatchCoord.Relative(value)
            '^' -> BatchCoord.Local(value)
            else -> BatchCoord.Absolute(value)
        }
    }

    fun color(raw: String, key: String): work.nekow.particledrawing.api.Color =
        ParticleCommandOptionParser.parseColor(unquote(raw))
}



