package work.nekow.particledrawing.animation.script

import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.light.LightShape

internal const val CTX_NAME = "this"
// 脚本值的强制转换与 spawn 配置解析：动态类型到宿主字段的翻译层。
// 与解释器的求值循环没有耦合，单独放一处，改 this 成员名时只需对照这里。
// 向量分量别名：r/g/b → x/y/z，a → w。
internal val COMP_ALIAS = mapOf("x" to "x", "y" to "y", "z" to "z", "w" to "w", "r" to "x", "g" to "y", "b" to "z", "a" to "w", "alpha" to "w")

/** 该向量是否含某规范化分量（x/y/z/w）。 */
internal fun hasComp(v: Any?, comp: String): Boolean = when (v) {
    is Vec2 -> comp == "x" || comp == "y"
    is Vec3 -> comp == "x" || comp == "y" || comp == "z"
    is Vec4 -> comp == "x" || comp == "y" || comp == "z" || comp == "w"
    else -> false
}

/** 写回单分量，返回新向量。 */
internal fun setVecComp(v: Any?, comp: String, value: Double): Any = when (v) {
    is Vec2 -> Vec2(if (comp == "x") value else v.x, if (comp == "y") value else v.y)
    is Vec3 -> Vec3(if (comp == "x") value else v.x, if (comp == "y") value else v.y, if (comp == "z") value else v.z)
    is Vec4 -> Vec4(if (comp == "x") value else v.x, if (comp == "y") value else v.y, if (comp == "z") value else v.z, if (comp == "w") value else v.w)
    else -> throw ScriptException("not a vector")
}

// this.spawn(config) 可选字段。
internal val SPAWN_CONFIG_FIELDS = setOf("position", "color", "velocity", "scale", "glow", "light", "life", "uv", "lightShape", "lightIntensity")

internal fun cfgVec(v: Any?, field: String, len: Int): List<Double> {
    if (isVec(v)) {
        val c = vecComps(v!!)
        if (c.size != len) throw ScriptException("spawn config '$field' requires a vec$len")
        return c
    }
    if (v is MutableList<*>) {
        if (v.size != len) throw ScriptException("spawn config '$field' requires an array of $len numbers")
        return v.mapIndexed { i, x -> (x as? Double) ?: throw ScriptException("spawn config '$field[$i]' requires a num") }
    }
    throw ScriptException("spawn config '$field' requires a vec$len or array of $len numbers")
}

internal fun cfgColor(v: Any?): List<Double> = when (v) {
    is ColorVal -> listOf(v.r, v.g, v.b, v.a)
    is Vec4 -> listOf(v.x, v.y, v.z, v.w)
    is Vec3 -> listOf(v.x, v.y, v.z)
    is MutableList<*> if (v.size == 3 || v.size == 4) ->
        v.mapIndexed { i, x -> (x as? Double) ?: throw ScriptException("spawn config 'color[$i]' requires a num") }

    else -> throw ScriptException("spawn config 'color' requires a color, vec3, vec4 or [r,g,b(,a)]")
}

/**
 * 粒子尺寸写法归一（spawn 配置的 scale 与脚本里的 p.scale 共用）：
 * num 写 [s, s, 1]（Z 恒为 1），vec2 或 2 元数组的 z 视为 1，vec3 或 3 元数组原样。
 *
 * @return 写法 0/2/3；不合法返回 -1，由调用方自行报错
 */
internal fun scaleShape(v: Any?, out: DoubleArray): Int {
    fun comp(x: Any?): Double = x as? Double ?: Double.NaN
    return when (v) {
        is Double -> { out[0] = v; out[1] = v; out[2] = 1.0; 0 }
        is Vec2 -> { out[0] = v.x; out[1] = v.y; out[2] = 1.0; 2 }
        is Vec3 -> { out[0] = v.x; out[1] = v.y; out[2] = v.z; 3 }
        is MutableList<*> if v.size == 2 -> {
            out[0] = comp(v[0]); out[1] = comp(v[1]); out[2] = 1.0
            if (out[0].isNaN() || out[1].isNaN()) -1 else 2
        }

        is MutableList<*> if v.size == 3 -> {
            out[0] = comp(v[0]); out[1] = comp(v[1]); out[2] = comp(v[2])
            if (out[0].isNaN() || out[1].isNaN() || out[2].isNaN()) -1 else 3
        }

        else -> -1
    }
}

/** 按 [ParticleHost.scaleDim] 记下的写法把尺寸读回脚本：标量原样返回，向量读成 vec3。 */
internal fun readScale(w: ParticleHost): Any =
    if (w.scaleDim == 0) w.scale[0] else Vec3(w.scale[0], w.scale[1], w.scale[2])

internal fun readLightShape(shape: LightShape?): List<Any> =
    if (shape == null) emptyList() else listOf(
        shape.range, shape.outerDeg, shape.innerDeg, shape.edge, shape.yawDeg, shape.pitchDeg,
        shape.falloff.name.lowercase(),
    )

internal fun parseLightShape(value: Any?): LightShape? {
    val list = value as? List<*> ?: throw ScriptException("lightShape requires an array")
    if (list.isEmpty()) return null
    if (list.size != 7) throw ScriptException("lightShape requires [range, outer, inner, edge, yaw, pitch, falloff]")
    fun number(i: Int) = list[i] as? Double ?: throw ScriptException("lightShape[$i] requires a num")
    val falloff = LightFalloff.entries.firstOrNull { it.name.lowercase() == list[6] }
        ?: throw ScriptException("lightShape falloff must be constant, linear, smooth, inverse or inverse_square")
    return try {
        LightShape(number(0), number(1), number(2), number(3), number(4), number(5), falloff).validated()
    } catch (e: IllegalArgumentException) {
        throw ScriptException(e.message ?: "invalid lightShape")
    }
}

internal fun parseLightIntensity(value: Any?): Double {
    val n = value as? Double
    if (n == null || !n.isFinite() || n < 0.0 || n > Float.MAX_VALUE) {
        throw ScriptException("lightIntensity requires a finite nonnegative num")
    }
    return n
}

internal fun applySpawnConfig(host: ParticleHost, config: Any?) {
    if (config !is ObjVal) throw ScriptException("this.spawn(config) requires an object")
    for ((key, v) in config.fields) {
        // spawn 配置是通用参数容器：未知键不当错误，作为粒子的命名标量存进 fields，脚本里用 p.键名 读写。
        if (key !in SPAWN_CONFIG_FIELDS) {
            host.fields[key] = v
            continue
        }
        when (key) {
            "position" -> { val c = cfgVec(v, key, 3); host.pos[0] = c[0]; host.pos[1] = c[1]; host.pos[2] = c[2] }
            "velocity" -> { val c = cfgVec(v, key, 3); host.vel[0] = c[0]; host.vel[1] = c[1]; host.vel[2] = c[2] }
            "color" -> {
                val c = cfgColor(v)
                host.color[0] = clamp01(c[0]); host.color[1] = clamp01(c[1]); host.color[2] = clamp01(c[2])
                host.color[3] = if (c.size == 4) clamp01(c[3]) else 1.0
            }
            "scale" -> {
                val dim = scaleShape(v, host.scale)
                if (dim < 0) throw ScriptException("spawn config 'scale' requires a num, vec2, vec3 or array of 2/3 numbers")
                host.scaleDim = dim
            }
            "glow" -> host.glow = v as? Boolean
                ?: (((v as? Double) ?: throw ScriptException("spawn config 'glow' requires a num")) > 0.5)
            "light" -> host.light = clampNum(jsRound((v as? Double) ?: throw ScriptException("spawn config 'light' requires a num")), 0.0, 15.0)
            "lightShape" -> host.lightShape = parseLightShape(v)
            "lightIntensity" -> host.lightIntensity = parseLightIntensity(v)
            "life" -> {
                val n = jsRound((v as? Double) ?: throw ScriptException("spawn config 'life' requires a num"))
                host.life = if (n.isFinite()) (if (n < 0.0) -1.0 else n) else -1.0
            }
            "uv" -> host.fields["uv"] = cfgVec(v, key, 2)
        }
    }
}
