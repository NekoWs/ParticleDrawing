package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.core.easing.EasingType

// 解析后的粒子动画（对应网页编辑器导出的 .pdrawc 播放文件）。
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

// 函数对象：公式代码块 + 变量，客户端实时求值生成派生粒子。
// st 之前派生粒子隐藏；uv 为函数对象级 UV（无贴图时渲染纯色方块）。
class FunctionObject(
    val id: String,
    val name: String,
    val center: DoubleArray,
    val source: String,
    val seed: Int,
    val vars: Map<String, FunctionVar>,
    val duration: Int,
    val uv: UvData? = null,
    val st: Int = 0,
    val ent: Entrance? = null,
    val fastMath: Boolean = false,
    val spinLocal: Boolean = false,
    val rotLocal: Boolean = false,
    /** true=派生粒子按渲染帧精确同步（无 50ms 延迟）；false=按 game tick 同步，与普通粒子渲染一致。 */
    val frameSync: Boolean = false,
)

/** 函数对象变量：数值基值 + 关键帧（关键帧非空时按时间轴插值，忽略基值）。base/kf 可变，支持服务端下发变量更新。 */
class FunctionVar(
    var base: Double,
    var kf: List<Keyframe>
)

/** 入场表现预设（粒子/函数对象的 ent 字段）。st 之前粒子完全不存在于渲染管线（隐藏门控，与 alpha 无关）；preset 目前支持 "fade"（出场后 dur tick 内 alpha 线性 0→1）。 */
data class Entrance(val preset: String, val dur: Int = 5)

/** 动画中的单个粒子。scale=[sx,sy,sz]（sx 参与 billboard 尺寸，sy/sz 暂存）；st 之前隐藏；ent 入场预设；life 为寿命 tick（-1 无限）。 */
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

/** 一条分量轨道，作用于一组目标（按 id / "g:name" / "f:fxId"）的某个分量。SET=绝对值，OP=增量。 */
class AnimTrack(
    val pr: TrackPr,
    val ids: List<String>,
    val keyframes: List<AnimKeyframe>,
    val mode: Mode
) {
    enum class Mode { SET, OP }
}

/** 单个关键帧。tick 触发时刻；value 目标值（rot 为度，渲染转弧度）；easing 到下一关键帧的缓动。 */
class AnimKeyframe(
    val tick: Int,
    val value: Double,
    val easing: EasingType
)

// 摄像机对象（.pdrawc 内嵌）：pos/target 世界坐标，roll 绕视线翻滚角（度），fov 视场角（度），rotLocal 公转空间（true=局部）。
// 关键帧走 AnimTrack（轨道 id "c:<id>"）；播放端不自动改玩家相机，仅提供数据供查询。
class AnimCamera(
    val id: String,
    val name: String,
    val pos: DoubleArray,
    val target: DoubleArray,
    val roll: Double,
    val fov: Double,
    val rotLocal: Boolean = true
)

// 动画时间轴长度（毫秒，与编辑器 maxMs 一致）：轨道最大关键帧毫秒、粒子 st/life 上界、函数对象 st+extent。
// 服务端与客户端共用，保证进度口径一致。
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
