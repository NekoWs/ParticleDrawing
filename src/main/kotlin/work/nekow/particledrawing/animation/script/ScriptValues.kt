package work.nekow.particledrawing.animation.script

import kotlin.math.ceil
import kotlin.math.floor
import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.TextChar
import work.nekow.particledrawing.animation.TextObject

/** 脚本运行时错误 / 解析错误；有行列号时附加在消息末尾。 */
class ScriptException(message: String, val line: Int? = null, val col: Int? = null) : RuntimeException(
    if (line != null && col != null) "$message (line $line, col $col)" else message
)

data class Vec2(val x: Double, val y: Double)

data class Vec3(val x: Double, val y: Double, val z: Double)

data class Vec4(val x: Double, val y: Double, val z: Double, val w: Double)

/** 3x3 行主序矩阵，元素为 3 个长度为 3 的行。 */
data class Mat3(val m: List<List<Double>>)

/** 4x4 行主序矩阵，元素为 4 个长度为 4 的行。 */
data class Mat4(val m: List<List<Double>>)

/** 用户函数值（闭包按顶层函数名查找）。 */
data class FuncVal(val name: String)

/** lambda 字面量值；[closure] 捕获创建时的作用域链。 */
class LambdaVal(
    val params: List<String>,
    val body: BlockNode,
    val closure: List<MutableMap<String, Any?>> = emptyList(),
)

/** 对象字面量值。 */
class ObjVal(val fields: MutableMap<String, Any?>)

/** RGBA 颜色值，分量范围 0..1；x/y/z/w 是 r/g/b/a 的别名。 */
data class ColorVal(val r: Double, val g: Double, val b: Double, val a: Double) {
    val x: Double get() = r
    val y: Double get() = g
    val z: Double get() = b
    val w: Double get() = a
}

/** 未定义值（let 未初始化、字段未设置、return 无表达式、缺省实参）。 */
object Undefined

fun isUndefined(v: Any?): Boolean = v === Undefined

/** 宿主侧粒子存储接口：粒子句柄桥接到此接口。 */
interface ParticleHost {
    val index: Int
    val pos: DoubleArray
    val color: DoubleArray
    val vel: DoubleArray
    /** 粒子尺寸三分量 [sx, sy, sz]；标量写 [s, s, 1]（Z 恒为 1），vec2 的 z 视为 1，vec3 原样。 */
    val scale: DoubleArray
    /** 最近一次写入 scale 的写法（0=标量、2=vec2、3=vec3）；读回时按写法返回 num 或 vec3。 */
    var scaleDim: Int
    var glow: Boolean
    var light: Double
    var life: Double
    /** 自转（XYZ 度）；billboard=true 时渲染端忽略。 */
    val rotation: DoubleArray
    /** 广告牌模式（始终朝向相机）。 */
    var billboard: Boolean
    /** 自转空间（true=local）。 */
    var spinLocal: Boolean
    val fields: MutableMap<String, Any?>
    fun kill()
}

/** 粒子句柄（spawn 模型）。 */
class ParticleValue(val host: ParticleHost)

/**
 * 函数对象级视图变换（脚本 this.viewScale / this.viewOffset）：整条迹线的全局增益与直流偏移。
 * 只作用于该函数对象自己的派生粒子，默认 1 / 0。
 */
class ViewTransform {
    /** 全局增益：位置与尺寸都乘它（均匀，不单独处理某个轴）。 */
    var scale: Double = 1.0
    /** 直流偏移（世界单位）：只加到位置上，vec2 写法时 z 为 0。 */
    val offset: DoubleArray = DoubleArray(3)

    /** 恢复默认（向后 seek 重建运行时用）。 */
    fun reset() {
        scale = 1.0
        offset[0] = 0.0; offset[1] = 0.0; offset[2] = 0.0
    }
}

/** 粒子列表（this.particles）。 */
class ParticleListValue(val hosts: MutableList<ParticleHost>) {
    val size: Int get() = hosts.size
    fun get(i: Int): ParticleValue = ParticleValue(hosts[i])
}

/** 文字对象句柄（this.get(名称) 返回，只读）。 */
class TextValue(val obj: TextObject)

/** 字符句柄（text.chars 元素，只读）。 */
class TextCharValue(val ch: TextChar)

/**
 * 音频资产句柄（this.get(名称) 返回，只读）。[at] 是取值基准（动画全局毫秒），
 * [speed] 是当刻倍速，内容位置 = (at - st) × speed。
 */
class AudioValue(val asset: AudioAsset, val at: Double, val playing: Boolean, val speed: Double = 1.0)

fun isNum(v: Any?): Boolean = v is Double
fun isBool(v: Any?): Boolean = v is Boolean
fun isString(v: Any?): Boolean = v is String
fun isVec(v: Any?): Boolean = v is Vec2 || v is Vec3 || v is Vec4
fun isMat(v: Any?): Boolean = v is Mat3 || v is Mat4
fun isArray(v: Any?): Boolean = v is MutableList<*>
fun isFunc(v: Any?): Boolean = v is FuncVal
fun isParticle(v: Any?): Boolean = v is ParticleValue
fun isParticleList(v: Any?): Boolean = v is ParticleListValue
fun isObj(v: Any?): Boolean = v is ObjVal
fun isLambda(v: Any?): Boolean = v is LambdaVal
fun isColor(v: Any?): Boolean = v is ColorVal
fun isText(v: Any?): Boolean = v is TextValue
fun isTextChar(v: Any?): Boolean = v is TextCharValue
fun isAudio(v: Any?): Boolean = v is AudioValue
fun isCallable(v: Any?): Boolean = isFunc(v) || isLambda(v)

fun vecDim(v: Any): Int = when (v) {
    is Vec2 -> 2
    is Vec3 -> 3
    is Vec4 -> 4
    else -> throw ScriptException("not a vector")
}

fun vecComps(v: Any): List<Double> = when (v) {
    is Vec2 -> listOf(v.x, v.y)
    is Vec3 -> listOf(v.x, v.y, v.z)
    is Vec4 -> listOf(v.x, v.y, v.z, v.w)
    else -> throw ScriptException("not a vector")
}

fun mkVec(dim: Int, comps: List<Double>): Any = when (dim) {
    2 -> Vec2(comps[0], comps[1])
    3 -> Vec3(comps[0], comps[1], comps[2])
    4 -> Vec4(comps[0], comps[1], comps[2], comps[3])
    else -> throw ScriptException("invalid vector dimension $dim")
}

fun typeName(v: Any?): String = when {
    isUndefined(v) -> "undefined"
    v == null -> "null"
    isNum(v) -> "num"
    isBool(v) -> "bool"
    isString(v) -> "string"
    isArray(v) -> "array"
    v is Vec2 -> "vec2"
    v is Vec3 -> "vec3"
    v is Vec4 -> "vec4"
    v is Mat3 -> "mat3"
    v is Mat4 -> "mat4"
    v is FuncVal -> "func"
    v is LambdaVal -> "lambda"
    v is ObjVal -> "obj"
    v is ColorVal -> "color"
    v is ParticleValue -> "particle"
    v is ParticleListValue -> "particleList"
    v is TextValue -> "text"
    v is TextCharValue -> "textChar"
    v is AudioValue -> "audio"
    else -> "unknown"
}

/** JS Math.trunc：向零取整。 */
fun jsTrunc(x: Double): Double = if (x < 0.0) ceil(x) else floor(x)

/** JS Math.round：半值向 +∞ 取整。 */
fun jsRound(x: Double): Double {
    if (x.isNaN()) return x
    if (x == 0.0) return x
    if (x > 0.0 && x < 0.5) return 0.0
    if (x < 0.0 && x >= -0.5) return -0.0
    return floor(x + 0.5)
}

/** JS ToInt32（`x | 0`）语义：有限数截断后按 2^32 取模映射到有符号 Int。 */
fun toInt32(x: Double): Int {
    if (!x.isFinite()) return 0
    val t = jsTrunc(x)
    val mod = ((t % 4294967296.0) + 4294967296.0) % 4294967296.0
    val u = mod.toLong()
    return (if (u >= 2147483648L) u - 4294967296L else u).toInt()
}

// JS Math.max(0, Math.min(1, x))：NaN 原样透传。
fun clamp01(x: Double): Double = when {
    x < 0.0 -> 0.0
    x > 1.0 -> 1.0
    else -> x
}

fun clampNum(x: Double, lo: Double, hi: Double): Double = when {
    x < lo -> lo
    x > hi -> hi
    else -> x
}
