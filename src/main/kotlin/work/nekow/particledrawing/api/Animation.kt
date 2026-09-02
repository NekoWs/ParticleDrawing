package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimCamera
import work.nekow.particledrawing.animation.AnimKeyframe
import work.nekow.particledrawing.animation.AnimParticle
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.Entrance
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.FunctionVar
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.animation.ServerAnimationManager
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.api.script.FuncsScope
import work.nekow.particledrawing.api.script.ProcessScope
import work.nekow.particledrawing.api.script.SetupScope
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import java.util.function.Consumer

/**
 * 代码直接生成的粒子动画，构建后可直接播放或链式操作。
 *
 * Kotlin（DSL）：
 * ```kotlin
 * val anim = Animation.create {
 *     loop = true
 *     particle { id = "p0"; pos = Vec3(0.0, 10.0, 0.0); color = Color.CYAN; scale = 1f; life = -1 }
 *     track { pr = "pos.x"; ids = listOf("p0"); keyframe(0, 0, 10.0, EasingType.LINEAR) }
 * }
 * anim.play(level.players(), origin)
 *     .updateVariable("rad", "4")
 *     .isActive()
 * ```
 *
 * Java（Builder）：
 * ```java
 * Animation anim = Animation.builder()
 *     .loop(true)
 *     .particle(p -> p.id("p0").pos(0, 10, 0).color(Color.CYAN).scale(1f).life(-1))
 *     .track(t -> t.pr("pos.x").ids("p0").keyframe(0, 0, EasingType.LINEAR))
 *     .build();
 * anim.play(level.players(), origin).updateVariable("rad", "4");
 * ```
 */
@Suppress("unused")
class Animation internal constructor(
    private val model: ParticleAnimation,
) {
    private var playbackId: UUID? = null
    private var level: ServerLevel? = null

    /**
     * 把动画下发给指定玩家并开始播放（服务端权威进度，与 .pdrawc 播放同一条客户端渲染链路）。
     * @param level 播放所在的服务端世界
     * @param players 接收播放的玩家
     * @param origin 播放原点（世界坐标）
     * @return 自身，支持链式调用
     */
    fun play(level: ServerLevel, players: Collection<ServerPlayer>, origin: Vec3): Animation {
        this.level = level
        playbackId = ServerAnimationManager.play(model, ParticleUtils.dimensionUUID(level), players, origin)
        return this
    }

    /**
     * [play] 的便捷重载：从首个玩家所在的维度推导 [ServerLevel]。
     * @param players 接收播放的玩家
     * @param origin 播放原点（世界坐标）
     */
    fun play(players: Collection<ServerPlayer>, origin: Vec3): Animation {
        require(players.isNotEmpty()) { "play 需要至少一个玩家" }
        val lvl: ServerLevel = players.first().level()
        return play(lvl, players, origin)
    }

    /** 停止本次播放（若尚未播放则为 no-op）。 */
    fun stop(): Animation {
        val id = playbackId ?: return this
        val lvl = level ?: return this
        ServerAnimationManager.stop(id, lvl.players())
        return this
    }

    /**
     * 运行时更新本次播放的函数对象变量（下一 tick 生效）。
     * @param name 变量名
     * @param value 变量值（数字字符串）
     */
    fun updateVariable(name: String, value: String): Animation {
        val id = playbackId ?: return this
        val lvl = level ?: return this
        ServerAnimationManager.updateVariable(id, name, value, lvl.players())
        return this
    }

    /** 本次播放是否仍在进行。 */
    fun isActive(): Boolean = playbackId?.let { ServerAnimationManager.isActive(it) } ?: false

    /** 仅供测试/内部使用：底层动画模型。 */
    internal val animationModel: ParticleAnimation get() = model

    companion object {
        /**
         * Kotlin DSL 入口：`Animation.create { ... }`。
         * @param block 动画构建 DSL 代码块
         */
        @JvmStatic
        fun create(block: AnimationDsl.() -> Unit): Animation = AnimationDsl().apply(block).build()

        /** Java Builder 入口：`Animation.builder()...build()`。 */
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/** Java 流式 Builder（Kotlin DSL 亦在其上实现）。 */
@Suppress("unused")
class Builder internal constructor() {
    private var loop = false
    private val particles = ArrayList<AnimParticle>()
    private val tracks = ArrayList<AnimTrack>()
    private val groups = LinkedHashMap<String, List<String>>()
    private val functions = ArrayList<FunctionObject>()
    private val textures = ArrayList<String>()
    private val groupUV = LinkedHashMap<String, UvData>()
    private val texData = LinkedHashMap<String, ByteArray>()
    private val groupSpinSpace = LinkedHashMap<String, Boolean>()
    private val groupRotSpace = LinkedHashMap<String, Boolean>()
    private val cameras = ArrayList<AnimCamera>()

    /**
     * 设置动画是否循环播放。
     * @param loop 是否循环播放
     */
    fun loop(loop: Boolean): Builder = apply { this.loop = loop }

    /**
     * 添加一个粒子。
     * @param block 粒子构建器配置
     */
    fun particle(block: Consumer<ParticleBuilder>): Builder = apply {
        val b = ParticleBuilder().also(block::accept)
        particles += b.build("p${particles.size}")
    }

    /**
     * 添加一条分量轨道。
     * @param block 轨道构建器配置
     */
    fun track(block: Consumer<TrackBuilder>): Builder = apply {
        tracks += TrackBuilder().also(block::accept).build()
    }

    /**
     * 添加一个函数对象。
     * @param block 函数对象构建器配置
     */
    fun function(block: Consumer<FunctionBuilder>): Builder = apply {
        val b = FunctionBuilder().also(block::accept)
        functions += b.build("fx${functions.size}")
    }

    /**
     * 添加一个摄像机对象。
     * @param block 摄像机对象构建器配置
     */
    fun camera(block: Consumer<CameraBuilder>): Builder = apply {
        val b = CameraBuilder().also(block::accept)
        cameras += b.build("cam${cameras.size}")
    }

    /**
     * 添加一个组及其成员。
     * @param name 组名
     * @param members 组成员粒子 id
     */
    fun group(name: String, members: List<String>): Builder = apply {
        groups[name] = members.toList()
    }

    /**
     * 添加一个组及其成员。
     * @param name 组名
     * @param members 组成员粒子 id
     */
    fun group(name: String, vararg members: String): Builder = group(name, members.toList())

    /**
     * 添加内嵌贴图。
     * @param name 贴图名
     * @param bytes PNG 字节
     */
    fun texture(name: String, bytes: ByteArray): Builder = apply {
        if (name !in textures) textures += name
        texData[name] = bytes
    }

    /**
     * 设置组级 UV。
     * @param name 组名
     * @param uv 组级 UV
     */
    fun groupUV(name: String, uv: UvData): Builder = apply { groupUV[name] = uv }

    /**
     * 设置组的自转空间。
     * @param name 组名
     * @param local 是否局部自转空间
     */
    fun groupSpinSpace(name: String, local: Boolean): Builder = apply { groupSpinSpace[name] = local }

    /**
     * 设置组的公转空间。
     * @param name 组名
     * @param local 是否局部公转空间
     */
    fun groupRotSpace(name: String, local: Boolean): Builder = apply { groupRotSpace[name] = local }

    /** 构建 [Animation]。 */
    fun build(): Animation = Animation(
        ParticleAnimation(
            loop, particles.toList(), tracks.toList(), groups, functions.toList(),
            textures.toList(), groupUV, texData, groupSpinSpace, groupRotSpace, cameras.toList(),
        )
    )
}

@Suppress("unused")
class ParticleBuilder internal constructor() {
    internal var pid: String? = null
    internal var pcolor: Color = Color.WHITE
    internal var pscale: FloatArray = floatArrayOf(1f, 1f, 1f)
    internal var pglowing: Boolean = false
    internal var plightLevel: Int = 15
    internal var ppos: Vec3 = Vec3.ZERO
    internal var pvel: Vec3 = Vec3.ZERO
    internal var puv: UvData? = null
    internal var pst: Int = 0
    internal var pent: Entrance? = null
    internal var plife: Int = -1

    /**
     * 设置粒子 id。
     * @param id 粒子 id
     */
    fun id(id: String): ParticleBuilder = apply { pid = id }
    /**
     * 设置粒子颜色。
     * @param color 粒子颜色
     */
    fun color(color: Color): ParticleBuilder = apply { pcolor = color }
    /**
     * 设置粒子 X/Y 缩放（billboard 无 Z）。
     * @param scale 缩放值
     */
    fun scale(scale: Float): ParticleBuilder = apply { pscale = floatArrayOf(scale, scale, 1f) }
    /**
     * 设置粒子三轴缩放。
     * @param sx X 缩放
     * @param sy Y 缩放
     * @param sz Z 缩放（数据保留）
     */
    fun scale(sx: Float, sy: Float, sz: Float): ParticleBuilder = apply { pscale = floatArrayOf(sx, sy, sz) }
    /**
     * 设置是否发光。
     * @param glowing 是否发光
     */
    fun glowing(glowing: Boolean): ParticleBuilder = apply { pglowing = glowing }
    /**
     * 设置发光光照等级。
     * @param level 光照等级（0-15）
     */
    fun lightLevel(level: Int): ParticleBuilder = apply { plightLevel = level.coerceIn(0, 15) }
    /**
     * 设置初始位置。
     * @param pos 初始位置
     */
    fun pos(pos: Vec3): ParticleBuilder = apply { ppos = pos }
    /**
     * 设置初始位置。
     * @param x 初始 X
     * @param y 初始 Y
     * @param z 初始 Z
     */
    fun pos(x: Number, y: Number, z: Number): ParticleBuilder = apply { ppos = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    /**
     * 设置初始速度。
     * @param vel 初始速度
     */
    fun vel(vel: Vec3): ParticleBuilder = apply { pvel = vel }
    /**
     * 设置初始速度。
     * @param x 速度 X
     * @param y 速度 Y
     * @param z 速度 Z
     */
    fun vel(x: Number, y: Number, z: Number): ParticleBuilder = apply { pvel = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    /**
     * 设置粒子级 UV。
     * @param uv 粒子级 UV
     */
    fun uv(uv: UvData): ParticleBuilder = apply { puv = uv }
    /**
     * 设置起始 tick。
     * @param st 起始 tick
     */
    fun st(st: Int): ParticleBuilder = apply { pst = st }
    /**
     * 设置入场表现预设。
     * @param ent 入场表现预设
     */
    fun ent(ent: Entrance): ParticleBuilder = apply { pent = ent }
    /**
     * 设置寿命。
     * @param life 寿命（tick，-1 无限）
     */
    fun life(life: Int): ParticleBuilder = apply { plife = life }

    internal fun build(defaultId: String): AnimParticle =
        AnimParticle(pid ?: defaultId, pcolor, pscale, pglowing, plightLevel, ppos, pvel, puv, pst, pent, plife)
}

@Suppress("unused")
class TrackBuilder internal constructor() {
    internal var tpr: String = ""
    internal var tmode: AnimTrack.Mode = AnimTrack.Mode.SET
    internal var tids: List<String> = emptyList()
    internal val tkeyframes = ArrayList<AnimKeyframe>()

    /**
     * 设置分量轨道标识。
     * @param pr 分量轨道标识（如 "pos.x"）
     */
    fun pr(pr: String): TrackBuilder = apply { tpr = pr }
    /**
     * 设置轨道模式。
     * @param mode 轨道模式（SET=绝对，OP=增量）
     */
    fun mode(mode: AnimTrack.Mode): TrackBuilder = apply { tmode = mode }
    /**
     * 设置轨道目标 id 列表。
     * @param ids 轨道目标 id 列表
     */
    fun ids(ids: List<String>): TrackBuilder = apply { tids = ids.toList() }
    /**
     * 设置轨道目标 id。
     * @param ids 轨道目标 id
     */
    fun ids(vararg ids: String): TrackBuilder = ids(ids.toList())
    /**
     * 添加一个关键帧。
     * @param tick 关键帧时刻
     * @param value 关键帧值
     * @param easing 缓动类型
     */
    fun keyframe(tick: Int, value: Double, easing: EasingType): TrackBuilder = apply {
        tkeyframes += AnimKeyframe(tick, value, easing)
    }
    /**
     * 添加一个关键帧。
     * @param tick 关键帧时刻
     * @param value 关键帧值
     * @param easing 缓动类型
     */
    fun keyframe(tick: Int, value: Number, easing: EasingType): TrackBuilder = keyframe(tick, value.toDouble(), easing)

    internal fun build(): AnimTrack = AnimTrack(tpr, tids, tkeyframes.toList(), tmode)
}

@Suppress("unused")
class FunctionBuilder internal constructor() {
    internal var fid: String? = null
    internal var fname: String? = null
    internal var fcenter: Vec3 = Vec3.ZERO
    internal var fcount: Int = 1
    internal var fsetup: String = ""
    internal var fprocess: String = ""
    internal var ffuncs: String = ""
    internal var fseed: Int = 0
    internal val fvars = LinkedHashMap<String, FunctionVar>()
    internal var fduration: Int = 0
    internal var fstep: Int = 0
    internal var fuv: UvData? = null
    internal var fst: Int = 0
    internal var fent: Entrance? = null
    internal var ffastMath: Boolean = false
    internal var fspinLocal: Boolean = false
    internal var frotLocal: Boolean = false

    /**
     * 设置函数对象 id。
     * @param id 函数对象 id
     */
    fun id(id: String): FunctionBuilder = apply { fid = id }
    /**
     * 设置函数对象名。
     * @param name 函数对象名
     */
    fun name(name: String): FunctionBuilder = apply { fname = name }
    /**
     * 设置对象中心。
     * @param center 对象中心
     */
    fun center(center: Vec3): FunctionBuilder = apply { fcenter = center }
    /**
     * 设置对象中心。
     * @param x 中心 X
     * @param y 中心 Y
     * @param z 中心 Z
     */
    fun center(x: Number, y: Number, z: Number): FunctionBuilder = apply { fcenter = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    /**
     * 设置派生粒子数量。
     * @param count 派生粒子数量
     */
    fun count(count: Int): FunctionBuilder = apply { fcount = count.coerceAtLeast(1) }
    /**
     * 设置 setup 脚本文本。
     * @param code setup 脚本文本
     */
    fun setup(code: String): FunctionBuilder = apply { fsetup = code }
    /**
     * 通过 DSL 生成 setup 脚本。
     * @param block setup 脚本 DSL
     */
    fun setup(block: Consumer<SetupScope>): FunctionBuilder = apply {
        fsetup = SetupScope().also(block::accept).build()
    }

    /**
     * 设置 process 脚本文本。
     * @param code process 脚本文本
     */
    fun process(code: String): FunctionBuilder = apply { fprocess = code }
    /**
     * 通过 DSL 生成 process 脚本。
     * @param block process 脚本 DSL
     */
    fun process(block: Consumer<ProcessScope>): FunctionBuilder = apply {
        fprocess = ProcessScope().also(block::accept).build()
    }

    /**
     * 设置顶层函数定义脚本文本。
     * @param code 顶层函数定义脚本文本
     */
    fun funcs(code: String): FunctionBuilder = apply { ffuncs = code }
    /**
     * 通过 DSL 生成顶层函数定义。
     * @param block 顶层函数定义 DSL
     */
    fun funcs(block: Consumer<FuncsScope>): FunctionBuilder = apply {
        ffuncs = FuncsScope().also(block::accept).build()
    }
    /**
     * 设置随机种子。
     * @param seed 随机种子
     */
    fun seed(seed: Int): FunctionBuilder = apply { fseed = seed }
    /**
     * 设置整体时长。
     * @param duration 整体时长（tick，0 无上限）
     */
    fun duration(duration: Int): FunctionBuilder = apply { fduration = duration.coerceAtLeast(0) }
    /**
     * 设置编辑器参数。
     * @param step 编辑器参数（播放端不使用）
     */
    fun step(step: Int): FunctionBuilder = apply { fstep = step.coerceAtLeast(0) }
    /**
     * 设置函数对象级 UV。
     * @param uv 函数对象级 UV
     */
    fun uv(uv: UvData): FunctionBuilder = apply { fuv = uv }
    /**
     * 设置起始 tick。
     * @param st 起始 tick
     */
    fun st(st: Int): FunctionBuilder = apply { fst = st.coerceAtLeast(0) }
    /**
     * 设置入场表现预设。
     * @param ent 入场表现预设
     */
    fun ent(ent: Entrance): FunctionBuilder = apply { fent = ent }
    /**
     * 设置是否使用快速数学近似。
     * @param fastMath 是否使用快速数学近似
     */
    fun fastMath(fastMath: Boolean): FunctionBuilder = apply { ffastMath = fastMath }
    /**
     * 设置自转空间。
     * @param local 是否局部自转空间
     */
    fun spinLocal(local: Boolean): FunctionBuilder = apply { fspinLocal = local }
    /**
     * 设置公转空间。
     * @param local 是否局部公转空间
     */
    fun rotLocal(local: Boolean): FunctionBuilder = apply { frotLocal = local }

    /**
     * 添加变量及关键帧。
     * @param name 变量名
     * @param base 变量基值
     * @param kf 变量关键帧
     */
    fun variable(name: String, base: Double, kf: List<Keyframe>): FunctionBuilder = apply { fvars[name] = FunctionVar(base, kf) }
    /**
     * 添加变量。
     * @param name 变量名
     * @param base 变量基值
     */
    fun variable(name: String, base: Double): FunctionBuilder = variable(name, base, emptyList())
    /**
     * 添加变量。
     * @param name 变量名
     * @param base 变量基值
     */
    fun variable(name: String, base: Number): FunctionBuilder = variable(name, base.toDouble())
    /**
     * 添加变量。
     * @param name 变量名
     */
    fun variable(name: String): FunctionBuilder = variable(name, 0.0)

    internal fun build(defaultId: String): FunctionObject {
        val resolvedId = fid ?: defaultId
        return FunctionObject(
            resolvedId, fname ?: resolvedId,
            doubleArrayOf(fcenter.x, fcenter.y, fcenter.z),
            fcount, fsetup, fprocess, ffuncs, fseed, fvars, fduration, fstep,
            fuv, fst, fent, ffastMath, fspinLocal, frotLocal,
        )
    }
}

@Suppress("unused")
class CameraBuilder internal constructor() {
    internal var cid: String? = null
    internal var cname: String? = null
    internal var cpos: Vec3 = Vec3.ZERO
    internal var ctarget: Vec3 = Vec3.ZERO
    internal var croll: Double = 0.0
    internal var cfov: Double = 70.0
    internal var crotLocal: Boolean = true

    /**
     * 设置摄像机 id。
     * @param id 摄像机 id
     */
    fun id(id: String): CameraBuilder = apply { cid = id }
    /**
     * 设置摄像机名。
     * @param name 摄像机名
     */
    fun name(name: String): CameraBuilder = apply { cname = name }
    /**
     * 设置摄像机位置。
     * @param pos 摄像机位置
     */
    fun pos(pos: Vec3): CameraBuilder = apply { cpos = pos }
    /**
     * 设置摄像机位置。
     * @param x 位置 X
     * @param y 位置 Y
     * @param z 位置 Z
     */
    fun pos(x: Number, y: Number, z: Number): CameraBuilder = apply { cpos = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    /**
     * 设置看向目标点。
     * @param target 看向目标点
     */
    fun target(target: Vec3): CameraBuilder = apply { ctarget = target }
    /**
     * 设置看向目标点。
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     */
    fun target(x: Number, y: Number, z: Number): CameraBuilder = apply { ctarget = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    /**
     * 设置翻滚角。
     * @param roll 翻滚角（度）
     */
    fun roll(roll: Double): CameraBuilder = apply { croll = roll }
    /**
     * 设置视场角。
     * @param fov 视场角（度）
     */
    fun fov(fov: Double): CameraBuilder = apply { cfov = fov }
    /**
     * 设置旋转空间。
     * @param local 是否局部旋转空间
     */
    fun rotLocal(local: Boolean): CameraBuilder = apply { crotLocal = local }

    internal fun build(defaultId: String): AnimCamera {
        val resolvedId = cid ?: defaultId
        return AnimCamera(
            resolvedId, cname ?: resolvedId,
            doubleArrayOf(cpos.x, cpos.y, cpos.z),
            doubleArrayOf(ctarget.x, ctarget.y, ctarget.z),
            croll, cfov, crotLocal,
        )
    }
}

/* =====================================================================
 * Kotlin DSL 糖：把 Java Builder 包装成属性赋值风格
 * ===================================================================== */

@Suppress("unused")
class AnimationDsl internal constructor() {
    var loop: Boolean = false
    private val b = Builder()

    /**
     * 添加一个粒子。
     * @param block 粒子 DSL
     */
    fun particle(block: ParticleDsl.() -> Unit) {
        b.particle { ParticleDsl(it).apply(block) }
    }

    /**
     * 添加一条轨道。
     * @param block 轨道 DSL
     */
    fun track(block: TrackDsl.() -> Unit) {
        b.track { TrackDsl(it).apply(block) }
    }

    /**
     * 添加一个函数对象。
     * @param block 函数对象 DSL
     */
    fun function(block: FunctionDsl.() -> Unit) {
        b.function { FunctionDsl(it).apply(block) }
    }

    /**
     * 添加一个摄像机对象。
     * @param block 摄像机对象 DSL
     */
    fun camera(block: CameraDsl.() -> Unit) {
        b.camera { CameraDsl(it).apply(block) }
    }

    /**
     * 添加一个组。
     * @param name 组名
     * @param members 组成员粒子 id
     */
    fun group(name: String, members: List<String>) { b.group(name, members) }
    /**
     * 添加一个组。
     * @param name 组名
     * @param members 组成员粒子 id
     */
    fun group(name: String, vararg members: String) { b.group(name, members.toList()) }
    /**
     * 添加内嵌贴图。
     * @param name 贴图名
     * @param bytes PNG 字节
     */
    fun texture(name: String, bytes: ByteArray) { b.texture(name, bytes) }
    /**
     * 设置组级 UV。
     * @param name 组名
     * @param uv 组级 UV
     */
    fun groupUV(name: String, uv: UvData) { b.groupUV(name, uv) }
    /**
     * 设置组自转空间。
     * @param name 组名
     * @param local 是否局部自转空间
     */
    fun groupSpinSpace(name: String, local: Boolean) { b.groupSpinSpace(name, local) }
    /**
     * 设置组公转空间。
     * @param name 组名
     * @param local 是否局部公转空间
     */
    fun groupRotSpace(name: String, local: Boolean) { b.groupRotSpace(name, local) }

    internal fun build(): Animation = b.loop(loop).build()
}

@Suppress("unused")
class ParticleDsl internal constructor(private val b: ParticleBuilder) {
    var id: String?
        get() = b.pid
        set(value) { b.pid = value }
    var color: Color
        get() = b.pcolor
        set(value) { b.pcolor = value }
    var scale: Float
        get() = b.pscale[0]
        set(value) { b.pscale = floatArrayOf(value, value, 1f) }
    var glowing: Boolean
        get() = b.pglowing
        set(value) { b.pglowing = value }
    var lightLevel: Int
        get() = b.plightLevel
        set(value) { b.plightLevel = value.coerceIn(0, 15) }
    var pos: Vec3
        get() = b.ppos
        set(value) { b.ppos = value }
    var vel: Vec3
        get() = b.pvel
        set(value) { b.pvel = value }
    var uv: UvData?
        get() = b.puv
        set(value) { b.puv = value }
    var st: Int
        get() = b.pst
        set(value) { b.pst = value.coerceAtLeast(0) }
    var ent: Entrance?
        get() = b.pent
        set(value) { b.pent = value }
    var life: Int
        get() = b.plife
        set(value) { b.plife = value }
}

@Suppress("unused")
class TrackDsl internal constructor(private val b: TrackBuilder) {
    var pr: String
        get() = b.tpr
        set(value) { b.tpr = value }
    var mode: AnimTrack.Mode
        get() = b.tmode
        set(value) { b.tmode = value }
    var ids: List<String>
        get() = b.tids
        set(value) { b.tids = value.toList() }

    /**
     * 添加一个关键帧。
     * @param tick 关键帧时刻
     * @param value 关键帧值
     * @param easing 缓动类型
     */
    fun keyframe(tick: Int, value: Number, easing: EasingType) {
        b.keyframe(tick, value.toDouble(), easing)
    }
}

@Suppress("unused")
class FunctionDsl internal constructor(private val b: FunctionBuilder) {
    var id: String?
        get() = b.fid
        set(value) { b.fid = value }
    var name: String?
        get() = b.fname
        set(value) { b.fname = value }
    var center: Vec3
        get() = b.fcenter
        set(value) { b.fcenter = value }
    var count: Int
        get() = b.fcount
        set(value) { b.fcount = value.coerceAtLeast(1) }
    /**
     * 设置 setup 脚本文本。
     * @param code setup 脚本文本
     */
    fun setup(code: String) { b.setup(code) }
    /**
     * 通过 DSL 生成 setup 脚本。
     * @param block setup 脚本 DSL
     */
    fun setup(block: SetupScope.() -> Unit) {
        b.setup { it.apply(block) }
    }

    /**
     * 设置 process 脚本文本。
     * @param code process 脚本文本
     */
    fun process(code: String) { b.process(code) }
    /**
     * 通过 DSL 生成 process 脚本。
     * @param block process 脚本 DSL
     */
    fun process(block: ProcessScope.() -> Unit) {
        b.process { it.apply(block) }
    }

    /**
     * 设置顶层函数定义脚本文本。
     * @param code 顶层函数定义脚本文本
     */
    fun funcs(code: String) { b.funcs(code) }
    /**
     * 通过 DSL 生成顶层函数定义。
     * @param block 顶层函数定义 DSL
     */
    fun funcs(block: FuncsScope.() -> Unit) {
        b.funcs { it.apply(block) }
    }
    var seed: Int
        get() = b.fseed
        set(value) { b.fseed = value }
    var duration: Int
        get() = b.fduration
        set(value) { b.fduration = value.coerceAtLeast(0) }
    var step: Int
        get() = b.fstep
        set(value) { b.fstep = value.coerceAtLeast(0) }
    var uv: UvData?
        get() = b.fuv
        set(value) { b.fuv = value }
    var st: Int
        get() = b.fst
        set(value) { b.fst = value.coerceAtLeast(0) }
    var ent: Entrance?
        get() = b.fent
        set(value) { b.fent = value }
    var fastMath: Boolean
        get() = b.ffastMath
        set(value) { b.ffastMath = value }
    var spinLocal: Boolean
        get() = b.fspinLocal
        set(value) { b.fspinLocal = value }
    var rotLocal: Boolean
        get() = b.frotLocal
        set(value) { b.frotLocal = value }

    /**
     * 添加变量。
     * @param name 变量名
     * @param base 变量基值
     */
    fun variable(name: String, base: Number = 0.0) { b.variable(name, base.toDouble()) }
    /**
     * 添加变量及关键帧。
     * @param name 变量名
     * @param base 变量基值
     * @param kf 变量关键帧
     */
    fun variable(name: String, base: Double, kf: List<Keyframe>) { b.variable(name, base, kf) }
}

@Suppress("unused")
class CameraDsl internal constructor(private val b: CameraBuilder) {
    var id: String?
        get() = b.cid
        set(value) { b.cid = value }
    var name: String?
        get() = b.cname
        set(value) { b.cname = value }
    var pos: Vec3
        get() = b.cpos
        set(value) { b.cpos = value }
    var target: Vec3
        get() = b.ctarget
        set(value) { b.ctarget = value }
    var roll: Double
        get() = b.croll
        set(value) { b.croll = value }
    var fov: Double
        get() = b.cfov
        set(value) { b.cfov = value }
    var rotLocal: Boolean
        get() = b.crotLocal
        set(value) { b.crotLocal = value }
}
