package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.core.easing.EasingType

/**
 * 解析后的粒子动画（对应网页编辑器导出的 .pdrawc 播放文件）。
 *
 * @param textures 播放文件引用的贴图名列表
 * @param groupUV 组级 UV 映射（组名 -> UV 参数）
 * @param texData 内嵌贴图数据（贴图名 -> PNG 字节数组）
 */
class ParticleAnimation(
    val loop: Boolean,
    val particles: List<AnimParticle>,
    val tracks: List<AnimTrack>,
    val groups: Map<String, List<String>>,
    val functions: List<FunctionObject> = emptyList(),
    val textures: List<String> = emptyList(),
    val groupUV: Map<String, UvData> = emptyMap(),
    val texData: Map<String, ByteArray> = emptyMap(),
    val groupSpinSpace: Map<String, Boolean> = emptyMap(),
    val groupRotSpace: Map<String, Boolean> = emptyMap(),
    val cameras: List<AnimCamera> = emptyList()
)

/**
 * 函数对象：公式代码块 + 变量，客户端实时求值生成派生粒子。
 *
 * @param uv 函数对象级 UV（作用域 f 级，派生粒子继承覆盖的根）；无贴图时派生粒子渲染为纯色方块
 * @param st 起始 tick：t < st 时全部派生粒子隐藏；条长（动画跨度）由 duration/变量关键帧决定
 */
class FunctionObject(
    val id: String,
    val name: String,
    val center: DoubleArray,
    val count: Int,
    val setup: String,
    val process: String,
    val funcs: String = "",
    val seed: Int,
    val vars: Map<String, FunctionVar>,
    val duration: Int,
    val step: Int,
    val uv: UvData? = null,
    val st: Int = 0,
    val ent: Entrance? = null,
    val fastMath: Boolean = false,
    val spinLocal: Boolean = false,
    val rotLocal: Boolean = false
)

/**
 * 函数对象变量：数值基值 + 关键帧（关键帧非空时按时间轴插值，忽略基值）。
 * base/kf 可变，支持服务端下发变量更新。
 */
class FunctionVar(
    var base: Double,
    var kf: List<Keyframe>
)

/**
 * 入场表现预设（粒子/函数对象的 `ent` 字段）。
 *
 * - `st` 之前粒子**完全不存在于渲染管线**（隐藏门控，与 alpha 无关）；
 * - [preset] 目前支持 `"fade"`（出场后 [dur] tick 内 alpha 线性 0→1）；
 *   高级入场动画在此扩展新 preset 约定即可，播放端按 preset 分派。
 */
data class Entrance(val preset: String, val dur: Int = 5)

/**
 * 动画中的单个粒子定义。
 *
 * @param scale 非均匀缩放 [sx, sy, sz]（编辑器 scale 拆分 XYZ；无贴图渲染为纯色方块，当前仅 sx 参与
 *              billboard 尺寸，sy/sz 暂存数据，见 HANDOFF 六.A.2 与 B.3）
 * @param st 起始 tick：t < st 时粒子隐藏；缺省 0（旧格式兼容）
 * @param ent 入场表现预设；null = 到点瞬间出现
 * @param life 寿命（tick）：t ≥ st+life 后粒子回收；-1 = 无限（活到动画结束），缺省 -1
 */
class AnimParticle(
    val id: String,
    val color: Color,
    val scale: FloatArray,
    val glowing: Boolean,
    val lightLevel: Int,
    val pos: Vec3,
    val vel: Vec3,
    val uv: UvData? = null,
    val st: Int = 0,
    val ent: Entrance? = null,
    val life: Int = -1
)

/**
 * 一条分量轨道，作用于一组目标（按 id 或 "g:name" 或 "f:fxId"）的某个分量。
 *
 * @param pr 分量轨道标识，如 "pos.x" / "rot.y" / "col.a" / "scl"
 * @param mode SET=关键帧值为绝对值；OP=关键帧值为增量（叠加到每个成员的基础值上）
 */
class AnimTrack(
    val pr: String,
    val ids: List<String>,
    val keyframes: List<AnimKeyframe>,
    val mode: Mode
) {
    enum class Mode { SET, OP }
}

/**
 * 单个关键帧。
 *
 * @param tick 触发时刻（tick）
 * @param value 目标标量值（rot 为度，渲染时转弧度）
 * @param easing 到下一个关键帧的缓动类型
 */
class AnimKeyframe(
    val tick: Int,
    val value: Double,
    val easing: EasingType
)

/**
 * 摄像机对象（v6 新增，.pdrawc 内嵌，供播放端按 id 查询位置/朝向/FOV 关键帧）。
 *
 * @param pos 基础位置 [x,y,z]（世界坐标）
 * @param target 看向目标点 [x,y,z]（世界坐标）；pitch/yaw 由 lookAt(pos, target) 自动计算
 * @param roll 翻滚角（度，绕视线方向，静态基础值，不走关键帧）
 * @param fov 基础视场角（度）
 *
 * 摄像机的位置/目标/FOV 关键帧走 [AnimTrack]（轨道 id 为 "c:<id>"，pr 为 pos.x/target.y/fov 等），
 * 播放端不自动改变玩家相机；仅提供数据供脚本/模组按需查询。
 */
class AnimCamera(
    val id: String,
    val name: String,
    val pos: DoubleArray,
    val target: DoubleArray,
    val roll: Double,
    val fov: Double
)

/**
 * 动画时间轴长度（tick，与编辑器 maxTick / 客户端播放器语义一致）：
 * 轨道最大关键帧 tick、粒子 st/life 上界、函数对象 st+extent（extent = max(duration, 变量关键帧最大 tick)）。
 * 服务端与客户端共用，保证进度计算口径一致。
 */
fun ParticleAnimation.timelineLength(): Int {
    var max = tracks.flatMap { it.keyframes }.maxOfOrNull { it.tick }?.toDouble() ?: 0.0
    for (p in particles) {
        if (p.st > max) max = p.st.toDouble()
        if (p.life >= 0 && p.st + p.life > max) max = (p.st + p.life).toDouble()
    }
    for (fx in functions) {
        var extent = fx.duration.toDouble()
        for (v in fx.vars.values) {
            val kfMax = v.kf.maxOfOrNull { it.tick } ?: continue
            if (kfMax > extent) extent = kfMax
        }
        if (fx.st + extent > max) max = fx.st + extent
    }
    return max.toInt()
}
