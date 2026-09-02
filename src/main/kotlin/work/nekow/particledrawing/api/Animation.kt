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
     * @return 自身，支持链式调用
     */
    fun play(level: ServerLevel, players: Collection<ServerPlayer>, origin: Vec3): Animation {
        this.level = level
        playbackId = ServerAnimationManager.play(model, ParticleUtils.dimensionUUID(level), players, origin)
        return this
    }

    /** [play] 的便捷重载：从首个玩家所在的维度推导 [ServerLevel]。 */
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

    /** 运行时更新本次播放的函数对象变量（下一 tick 生效）。 */
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
        /** Kotlin DSL 入口：`Animation.create { ... }`。 */
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

    fun loop(loop: Boolean): Builder = apply { this.loop = loop }

    fun particle(block: Consumer<ParticleBuilder>): Builder = apply {
        val b = ParticleBuilder().also(block::accept)
        particles += b.build("p${particles.size}")
    }

    fun track(block: Consumer<TrackBuilder>): Builder = apply {
        tracks += TrackBuilder().also(block::accept).build()
    }

    fun function(block: Consumer<FunctionBuilder>): Builder = apply {
        val b = FunctionBuilder().also(block::accept)
        functions += b.build("fx${functions.size}")
    }

    fun camera(block: Consumer<CameraBuilder>): Builder = apply {
        val b = CameraBuilder().also(block::accept)
        cameras += b.build("cam${cameras.size}")
    }

    fun group(name: String, members: List<String>): Builder = apply {
        groups[name] = members.toList()
    }

    fun group(name: String, vararg members: String): Builder = group(name, members.toList())

    fun texture(name: String, bytes: ByteArray): Builder = apply {
        if (name !in textures) textures += name
        texData[name] = bytes
    }

    fun groupUV(name: String, uv: UvData): Builder = apply { groupUV[name] = uv }

    fun groupSpinSpace(name: String, local: Boolean): Builder = apply { groupSpinSpace[name] = local }

    fun groupRotSpace(name: String, local: Boolean): Builder = apply { groupRotSpace[name] = local }

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

    fun id(id: String): ParticleBuilder = apply { pid = id }
    fun color(color: Color): ParticleBuilder = apply { pcolor = color }
    fun scale(scale: Float): ParticleBuilder = apply { pscale = floatArrayOf(scale, scale, 1f) }
    fun scale(sx: Float, sy: Float, sz: Float): ParticleBuilder = apply { pscale = floatArrayOf(sx, sy, sz) }
    fun glowing(glowing: Boolean): ParticleBuilder = apply { pglowing = glowing }
    fun lightLevel(level: Int): ParticleBuilder = apply { plightLevel = level.coerceIn(0, 15) }
    fun pos(pos: Vec3): ParticleBuilder = apply { ppos = pos }
    fun pos(x: Number, y: Number, z: Number): ParticleBuilder = apply { ppos = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    fun vel(vel: Vec3): ParticleBuilder = apply { pvel = vel }
    fun vel(x: Number, y: Number, z: Number): ParticleBuilder = apply { pvel = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    fun uv(uv: UvData): ParticleBuilder = apply { puv = uv }
    fun st(st: Int): ParticleBuilder = apply { pst = st }
    fun ent(ent: Entrance): ParticleBuilder = apply { pent = ent }
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

    fun pr(pr: String): TrackBuilder = apply { tpr = pr }
    fun mode(mode: AnimTrack.Mode): TrackBuilder = apply { tmode = mode }
    fun ids(ids: List<String>): TrackBuilder = apply { tids = ids.toList() }
    fun ids(vararg ids: String): TrackBuilder = ids(ids.toList())
    fun keyframe(tick: Int, value: Double, easing: EasingType): TrackBuilder = apply {
        tkeyframes += AnimKeyframe(tick, value, easing)
    }
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

    fun id(id: String): FunctionBuilder = apply { fid = id }
    fun name(name: String): FunctionBuilder = apply { fname = name }
    fun center(center: Vec3): FunctionBuilder = apply { fcenter = center }
    fun center(x: Number, y: Number, z: Number): FunctionBuilder = apply { fcenter = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    fun count(count: Int): FunctionBuilder = apply { fcount = count.coerceAtLeast(1) }
    fun setup(code: String): FunctionBuilder = apply { fsetup = code }
    fun setup(block: Consumer<SetupScope>): FunctionBuilder = apply {
        fsetup = SetupScope().also(block::accept).build()
    }

    fun process(code: String): FunctionBuilder = apply { fprocess = code }
    fun process(block: Consumer<ProcessScope>): FunctionBuilder = apply {
        fprocess = ProcessScope().also(block::accept).build()
    }

    fun funcs(code: String): FunctionBuilder = apply { ffuncs = code }
    fun funcs(block: Consumer<FuncsScope>): FunctionBuilder = apply {
        ffuncs = FuncsScope().also(block::accept).build()
    }
    fun seed(seed: Int): FunctionBuilder = apply { fseed = seed }
    fun duration(duration: Int): FunctionBuilder = apply { fduration = duration.coerceAtLeast(0) }
    fun step(step: Int): FunctionBuilder = apply { fstep = step.coerceAtLeast(0) }
    fun uv(uv: UvData): FunctionBuilder = apply { fuv = uv }
    fun st(st: Int): FunctionBuilder = apply { fst = st.coerceAtLeast(0) }
    fun ent(ent: Entrance): FunctionBuilder = apply { fent = ent }
    fun fastMath(fastMath: Boolean): FunctionBuilder = apply { ffastMath = fastMath }
    fun spinLocal(local: Boolean): FunctionBuilder = apply { fspinLocal = local }
    fun rotLocal(local: Boolean): FunctionBuilder = apply { frotLocal = local }

    fun variable(name: String, base: Double, kf: List<Keyframe>): FunctionBuilder = apply { fvars[name] = FunctionVar(base, kf) }
    fun variable(name: String, base: Double): FunctionBuilder = variable(name, base, emptyList())
    fun variable(name: String, base: Number): FunctionBuilder = variable(name, base.toDouble())
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

    fun id(id: String): CameraBuilder = apply { cid = id }
    fun name(name: String): CameraBuilder = apply { cname = name }
    fun pos(pos: Vec3): CameraBuilder = apply { cpos = pos }
    fun pos(x: Number, y: Number, z: Number): CameraBuilder = apply { cpos = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    fun target(target: Vec3): CameraBuilder = apply { ctarget = target }
    fun target(x: Number, y: Number, z: Number): CameraBuilder = apply { ctarget = Vec3(x.toDouble(), y.toDouble(), z.toDouble()) }
    fun roll(roll: Double): CameraBuilder = apply { croll = roll }
    fun fov(fov: Double): CameraBuilder = apply { cfov = fov }
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

    fun particle(block: ParticleDsl.() -> Unit) {
        b.particle { ParticleDsl(it).apply(block) }
    }

    fun track(block: TrackDsl.() -> Unit) {
        b.track { TrackDsl(it).apply(block) }
    }

    fun function(block: FunctionDsl.() -> Unit) {
        b.function { FunctionDsl(it).apply(block) }
    }

    fun camera(block: CameraDsl.() -> Unit) {
        b.camera { CameraDsl(it).apply(block) }
    }

    fun group(name: String, members: List<String>) { b.group(name, members) }
    fun group(name: String, vararg members: String) { b.group(name, members.toList()) }
    fun texture(name: String, bytes: ByteArray) { b.texture(name, bytes) }
    fun groupUV(name: String, uv: UvData) { b.groupUV(name, uv) }
    fun groupSpinSpace(name: String, local: Boolean) { b.groupSpinSpace(name, local) }
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
    fun setup(code: String) { b.setup(code) }
    fun setup(block: SetupScope.() -> Unit) {
        b.setup { it.apply(block) }
    }

    fun process(code: String) { b.process(code) }
    fun process(block: ProcessScope.() -> Unit) {
        b.process { it.apply(block) }
    }

    fun funcs(code: String) { b.funcs(code) }
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

    fun variable(name: String, base: Number = 0.0) { b.variable(name, base.toDouble()) }
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