package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.CurveChannel
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.core.easing.EasingCurve
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.util.rotateAround
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

private val LINEAR = EasingCurve(0.0, 0.0, 1.0, 1.0)

/** 缓动三元组：当前值 / 目标值 / 缓动起点。 */
private class EaseVar<T>(var cur: T, var tgt: T, var start: T)

/** 缓动计时状态：时刻用引擎毫秒（每 tick +50），关卡暂停时缓动也停住。 */
private class EaseState(
    var active: Boolean = false,
    var startMs: Long = -1L,
    var durationMs: Long = 0L,
    var easing: EasingCurve = LINEAR
)

/**
 * 光强目标的热更过渡：在原始亮度上叠加一段逐渐衰减到 0 的偏移。
 *
 * 起点偏移 = 当前实际亮度 − 新目标在当刻的原始亮度，于是更新那一刻画面亮度连续，
 * 不会先跳回满光再开始淡。之后曲线、透明度、尺寸包络照常逐帧变化，偏移只负责平滑接续。
 */
private class LightEase(
    var offset: Float = 0f,
    var startMs: Long = -1L,
    var durationMs: Long = 0L,
    var easing: EasingCurve = LINEAR,
)

/**
 * 渲染粒子：保存可视化状态并支持缓动过渡与速度积分。
 *
 * @param lightLevel 发光粒子向外发出的光照等级 (0-15)，仅在 glowing 为 true 时生效
 * @param lifetimeTicks 存活 tick 数；<=0 表示永久。按引擎 tick 计（关卡暂停时不流逝）
 * @param uv 贴图取景框；null = 纯白方块
 * @param lifeCurve 逐粒子寿命曲线（寿命内的颜色/尺寸/光强乘数）；null = 恒定外观
 * @param prev 上一 tick 的位置，与 [position] 组成插值段；null = 直接出生
 * @param lightIntensity 出生时的对外光强静态乘数（>= 0；1 = 原始亮度）
 */
@Suppress("unused")
class RenderParticle(
    private val id: UUID,
    position: Vec3,
    color: Color,
    scale: Float,
    private var glowing: Boolean,
    private var lightLevel: Int,
    lifetimeTicks: Int,
    var uv: UvData? = null,
    private val lifeCurve: ParticleLifeCurve? = null,
    prev: Vec3? = null,
    lightIntensity: Float = 1f,
) {

    // 位置 / 颜色 / 缩放（直接缓动）
    private val pos = EaseVar(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)
    private val col = EaseVar(Color.BLACK, Color.BLACK, Color.BLACK)
    private val scl = EaseVar(0f, 0f, 0f)

    // 非均匀缩放三分量 [sx, sy, sz]；标量路径写 [s, s, 1]（粒子模型的 Z 恒为 1）
    private var sclArray: FloatArray = floatArrayOf(0f, 0f, 0f)

    // 缩放缓动的起止基准尺寸。每帧按分数刻从 from 插到 to，sclArray 只保存本 tick 的落地值。
    private var scaleFromX: Float = 0f
    private var scaleFromY: Float = 0f
    private var scaleToX: Float = 0f
    private var scaleToY: Float = 0f

    /** 光照包络采样尺寸的复用缓冲，避免每颗每帧分配。 */
    private val lightScaleBuf = FloatArray(3)

    // 旋转 / 平移 / 偏移（绕轴心独立缓动后叠加）
    private val rot = EaseVar(DoubleArray(3), DoubleArray(3), DoubleArray(3))
    private val trans = EaseVar(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)
    private val off = EaseVar(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)

    private val posEase = EaseState()
    private val colEase = EaseState()
    private val scaleEase = EaseState()
    private val rotEase = EaseState()
    private val transEase = EaseState()
    private val offEase = EaseState()

    private var rotPivot = Vec3.ZERO
    private var velocity = Vec3.ZERO

    // 加速度（服务端权威力）：>0 = 还有这么多 tick；<0 = 无限；0 = 无
    private var acceleration = Vec3.ZERO
    private var accelTicks = 0
    private var snapNextSync = false

    /** 存活 tick 数；<=0 = 永久。 */
    private var lifetimeTicks: Int = lifetimeTicks

    /** 已经活过的引擎 tick 数。 */
    private var ticksAlive: Int = 0

    /** 出生时的引擎 tick 序号；Long.MIN_VALUE = 直接调用、不做出生 tick 过滤（测试用）。 */
    private var bornSequence: Long = Long.MIN_VALUE

    /** 出生在本 tick 的哪个渲染进度（0..1）；渲染帧内生成的粒子用它把起点对齐到出生那一刻。 */
    private var spawnPartial: Float = 0f

    /** 引擎时钟（毫秒，每 tick +50）：缓动与寿命曲线都按它推进。 */
    private var engineMs: Long = 0L

    // 上一 game tick 的位置（供动态光照按 partialTick 插值）
    private var prevX: Double
    private var prevY: Double
    private var prevZ: Double

    // 寿命曲线：当前采样出的乘数（同一帧内多路读取只算一次）
    private var curveSampleTicks = Float.NaN
    private var mulR = 1f
    private var mulG = 1f
    private var mulB = 1f
    private var mulA = 1f
    private var mulScale = 1f
    private var mulLight = 1f

    /** 出生时的对外光强乘数；运行时热更改的是它。 */
    private var baseLightIntensity: Float = lightIntensity.coerceAtLeast(0f)

    /** 出生时的视觉尺寸（各向异性取长轴），光强包络用「当前尺寸 ÷ 出生尺寸」。 */
    private var spawnSize: Float = max(abs(scale), 0f)

    /** 运行时目标热更的过渡；startMs < 0 表示没有过渡。 */
    private var lightEase = LightEase()

    /** 寿命到点但仍留一帧画零末端（仅当曲线末端确实收到零）。 */
    private var expiring = false

    init {
        pos.cur = position
        pos.tgt = position
        col.cur = color
        col.tgt = color
        scl.cur = scale
        scl.tgt = scale
        sclArray[0] = scale; sclArray[1] = scale; sclArray[2] = 1f
        val start = prev ?: position
        prevX = start.x
        prevY = start.y
        prevZ = start.z
        scaleFromX = scale
        scaleFromY = scale
        scaleToX = scale
        scaleToY = scale
        this.lightLevel = lightLevel.coerceIn(0, 15)
    }

    fun id(): UUID = id
    fun glowing(): Boolean = glowing
    fun lightLevel(): Int = lightLevel

    /**
     * 生成后经过的 tick 数（引擎时钟：关卡暂停时不增长）。
     *
     * [partialTick] 是渲染帧在上一 tick 与本 tick 之间的进度；曲线与动态光照用分数年龄采样，
     * 短寿命粒子才能在两 tick 之间走到中间值和零末端。
     */
    fun ageTicks(partialTick: Float = 0f): Float =
        engineMs / 50.0f + partialTick.coerceIn(0f, 1f) - spawnPartial

    /**
     * 当前寿命曲线的乘数，按 [CurveChannel] 顺序写进 [out] = `[r, g, b, a, scale, light]`；
     * 没有曲线时返回 false 且不改动 [out]。数组长度不足 6 时最后一项不写。
     */
    fun curveMultipliers(out: FloatArray, partialTick: Float = 0f): Boolean {
        if (lifeCurve == null) return false
        refreshCurve(partialTick)
        out[0] = mulR
        out[1] = mulG
        out[2] = mulB
        out[3] = mulA
        out[4] = mulScale
        if (out.size > 5) out[5] = mulLight
        return true
    }

    /** 寿命曲线调制后的透明度（发光粒子的可见性判断用；无曲线时就是自身 alpha）。 */
    fun effectiveAlpha(partialTick: Float = 0f): Float {
        if (lifeCurve == null) return col.cur.a
        refreshCurve(partialTick)
        return col.cur.a * mulA
    }

    /**
     * 当前对外光照强度（0-15 的有效值）。
     *
     * 基础等级 × 静态乘数 × 光强曲线 × 透明度 × 尺寸包络，再加运行时热更的接续偏移。
     * 透明度与尺寸来自同一帧的实际外观，所以出生、缩片、淡出与表达式 presence
     * 都会连续收束；显式 remove 或死亡后光源不再被收集。
     */
    fun lightIntensity(partialTick: Float = 0f): Float {
        if (!glowing || lightLevel <= 0) return 0f
        val value = rawLightIntensity(partialTick) + lightTransitionOffset(partialTick)
        return if (value.isFinite()) value.coerceAtLeast(0f) else 0f
    }

    /** 不含运行时热更过渡的亮度：静态乘数、曲线、透明度与尺寸包络。 */
    private fun rawLightIntensity(partialTick: Float): Float {
        if (lifeCurve != null) refreshCurve(partialTick)
        val alpha = (col.cur.a * if (lifeCurve != null) mulA else 1f).coerceIn(0f, 1f)
        val curveScale = if (lifeCurve != null) mulScale else 1f
        val curveLight = if (lifeCurve != null) mulLight else 1f
        visualScaleAt(partialTick, lightScaleBuf, applyCurve = false)
        val baseSize = max(abs(lightScaleBuf[0]), abs(lightScaleBuf[1]))
        val size = (baseSize / max(spawnSize, 1e-6f)).coerceIn(0f, 1f)
        val value = lightLevel * baseLightIntensity * curveLight * alpha * size * curveScale.coerceIn(0f, 1f)
        return if (value.isFinite()) value.coerceAtLeast(0f) else 0f
    }

    /** 当前视觉尺寸（各向异性取两根渲染轴里较长的一根）。 */
    private fun currentSize(): Float = max(abs(sclArray[0]), abs(sclArray[1]))

    /**
     * 本帧实际视觉尺寸（编辑器单位，已乘寿命曲线）。
     *
     * [applyCurve] = false 时只给基态尺寸（动画/程序直写接管外观时，曲线不再叠乘）。
     * 数组顺序 `[长轴, 短轴, Z]`；Z 只存储，不参与广告牌渲染。
     */
    fun visualScaleAt(partialTick: Float, out: FloatArray, applyCurve: Boolean = true) {
        out[0] = baseScaleXAt(partialTick)
        out[1] = baseScaleYAt(partialTick)
        out[2] = sclArray[2]
        if (!applyCurve || lifeCurve == null) return
        refreshCurve(partialTick)
        out[0] *= mulScale
        out[1] *= mulScale
    }

    /** 本帧基准尺寸的 X 轴：缩放缓动按分数刻插值，未缓动时就是 [sclArray] 的当前值。 */
    private fun baseScaleXAt(partialTick: Float): Float = baseScaleAt(partialTick, scaleFromX, scaleToX, sclArray[0])

    private fun baseScaleYAt(partialTick: Float): Float = baseScaleAt(partialTick, scaleFromY, scaleToY, sclArray[1])

    private fun baseScaleAt(partialTick: Float, from: Float, to: Float, current: Float): Float {
        val ease = scaleEase
        if (ease.startMs < 0L) return current
        if (ease.durationMs <= 0L) return to
        val elapsed = (engineMs - ease.startMs) + partialTick.coerceIn(0f, 1f) * 50f
        val k = (elapsed / ease.durationMs).coerceIn(0f, 1f)
        if (k >= 1f) return to
        val e = ease.easing.evaluate(k)
        return from + (to - from) * e
    }

    /** 运行时热更的过渡偏移；没有过渡或已经走完时为 0。 */
    private fun lightTransitionOffset(partialTick: Float): Float {
        val ease = lightEase
        if (ease.startMs < 0L || ease.durationMs <= 0L) return 0f
        val elapsed = (engineMs - ease.startMs) + partialTick.coerceIn(0f, 1f) * 50f
        val k = (elapsed / ease.durationMs).coerceIn(0f, 1f)
        return ease.offset * (1f - ease.easing.evaluate(k))
    }

    /**
     * 把运行时目标乘数缓动到 [multiplier]。
     *
     * 过渡起点取「当前实际亮度 − 新目标在当刻的原始亮度」，所以更新那一刻画面亮度不跳，
     * 目标为 0 时也能从当前亮度平滑淡出；时长 0 直接落地。
     */
    fun applyLightIntensity(multiplier: Float, durationMs: Long, easing: EasingType) {
        require(multiplier.isFinite() && multiplier >= 0f) { "光强乘数必须是非负有限值" }
        if (durationMs <= 0L) {
            baseLightIntensity = multiplier
            lightEase = LightEase()
            return
        }
        val current = lightIntensity(0f)
        baseLightIntensity = multiplier
        val rawStart = rawLightIntensity(0f)
        lightEase = LightEase(current - rawStart, engineMs, durationMs, easing.curve)
    }

    /** 出生后捕获尺寸基准（各向异性在设置三元组之后调用）。 */
    fun captureSpawnSize() {
        spawnSize = currentSize()
    }

    /** 曲线是否驱动颜色/透明度（ALPHA 与 RGB 都算颜色通道）。 */
    fun hasColorCurve(): Boolean = lifeCurve?.curves?.any {
        it.channel == CurveChannel.ALPHA || it.channel == CurveChannel.RED ||
            it.channel == CurveChannel.GREEN || it.channel == CurveChannel.BLUE
    } == true

    /** 曲线是否驱动尺寸。 */
    fun hasScaleCurve(): Boolean =
        lifeCurve?.curves?.any { it.channel == CurveChannel.SCALE } == true

    /** 缩放缓动是否还在进行；渲染帧需要按分数刻重写桥接尺寸。 */
    fun isScaleEasing(): Boolean = scaleEase.startMs >= 0L

    /** 这套曲线是否改外观；只有 LIGHT 通道时不必每帧重写桥接的颜色/尺寸。 */
    fun hasAppearanceCurve(): Boolean = hasColorCurve() || hasScaleCurve()

    /** 这条曲线在寿命末尾是否确实收到零：只有这种粒子值得多留一帧画零末端。 */
    fun needsFinalFrame(): Boolean {
        if (lifetimeTicks <= 0) return false
        val curve = lifeCurve ?: return false
        val end = lifetimeTicks.toFloat()
        val alpha = curve.valueAt(CurveChannel.ALPHA, end)
        val scale = curve.valueAt(CurveChannel.SCALE, end)
        return alpha <= 1e-4f || scale <= 1e-4f
    }

    fun isExpiring(): Boolean = expiring

    /** 标记为「已到寿命、还留一帧收尾」。 */
    fun markExpiring() {
        expiring = true
        curveSampleTicks = Float.NaN
    }

    /** 一帧内只按当前时刻采样一次曲线。 */
    private fun refreshCurve(partialTick: Float) {
        val curve = lifeCurve ?: return
        val t = ageTicks(partialTick)
        if (t == curveSampleTicks) return
        curveSampleTicks = t
        var r = 1f; var g = 1f; var b = 1f; var a = 1f; var s = 1f; var l = 1f
        for (c in curve.curves) {
            val v = c.valueAt(t)
            when (c.channel) {
                CurveChannel.ALPHA -> a *= v
                CurveChannel.SCALE -> s *= v
                CurveChannel.RED -> r *= v
                CurveChannel.GREEN -> g *= v
                CurveChannel.BLUE -> b *= v
                CurveChannel.LIGHT -> l *= v
            }
        }
        mulR = r; mulG = g; mulB = b; mulA = a; mulScale = s; mulLight = l
    }

    fun x(): Double = pos.cur.x
    fun y(): Double = pos.cur.y
    fun z(): Double = pos.cur.z
    fun r(): Float = col.cur.r
    fun g(): Float = col.cur.g
    fun b(): Float = col.cur.b
    fun a(): Float = col.cur.a
    fun scale(): Float = scl.cur
    /** 非均匀缩放三分量 [sx, sy, sz]（动画系统使用）。 */
    fun scaleArray(): FloatArray = sclArray

    // 每帧 partialTick 插值后的位置（动态光照用）
    fun interpolatedX(partialTick: Float): Double = prevX + (pos.cur.x - prevX) * partialTick
    fun interpolatedY(partialTick: Float): Double = prevY + (pos.cur.y - prevY) * partialTick
    fun interpolatedZ(partialTick: Float): Double = prevZ + (pos.cur.z - prevZ) * partialTick

    fun isAlive(): Boolean = lifetimeTicks <= 0 || ticksAlive < lifetimeTicks
    fun isDead(): Boolean = !isAlive()

    /** 记下出生时的引擎 tick 序号：出生那一 tick 不推进年龄，先让渲染帧看到 age 0。 */
    fun markBorn(sequence: Long) {
        bornSequence = sequence
    }

    /** 渲染帧内出生：记下出生时的 partialTick，曲线与光照从 age 0 开始。 */
    fun markSpawnedDuringRender(partialTick: Float) {
        spawnPartial = partialTick.coerceIn(0f, 1f)
    }

    /**
     * 这一帧是否该推进年龄。[previousSequence] 是推进前的引擎 tick 序号；
     * 出生在同一序号里的粒子先跳过一帧，下一 tick 才开始计年龄。
     */
    fun shouldAdvance(previousSequence: Long): Boolean =
        bornSequence == Long.MIN_VALUE || bornSequence < previousSequence

    /**
     * 推进一个引擎 tick：寿命与缓动时钟一起走。引擎时钟在关卡暂停时不推进，
     * 所以 `lifetime(40)` 的 40 tick 不会因暂停被一次性判死。
     */
    fun advanceEngine() {
        engineMs += 50L
        if (lifetimeTicks > 0) ticksAlive++
    }

    /** 设置位置/颜色/缩放的缓动目标（标量缩放）。 */
    fun setTarget(position: Vec3, color: Color, scale: Float, easingType: EasingType, durationMs: Long) {
        velocity = Vec3.ZERO
        rotEase.active = false
        transEase.active = false
        offEase.active = false
        pos.start = pos.cur
        pos.tgt = position
        col.start = col.cur
        col.tgt = color
        beginScaleTarget(scale, durationMs, easingType)
        posEase.easing = easingType.curve
        colEase.easing = easingType.curve
        val now = engineMs
        posEase.startMs = now
        posEase.durationMs = durationMs
        colEase.startMs = now
        colEase.durationMs = durationMs
        if (durationMs == 0L) {
            snapNextSync = true
            finishColorScale()
        }
    }

    /**
     * 设置颜色与缩放的缓动目标（标量缩放）。
     *
     * [color] = null 表示只改尺寸，[scale] = null 表示只改颜色；没给的通道保持当前缓动，
     * 不会因为一条指令顺手重置另一条通道。缩放为各向异性时按当前两轴比例缩放到长轴目标。
     */
    fun setTargetColorScale(color: Color?, scale: Float?, easingType: EasingType, durationMs: Long) {
        if (color != null) {
            col.start = col.cur
            col.tgt = color
            colEase.easing = easingType.curve
            colEase.startMs = engineMs
            colEase.durationMs = durationMs
        }
        if (scale != null) beginScaleTarget(scale, durationMs, easingType)
    }

    /**
     * 记录缩放缓动的起止基准。
     *
     * 各向异性粒子按当前两轴比例缩放到长轴目标，缩到 0 时两轴同时归零；
     * 目标只写入 [scaleToX]/[scaleToY]，本 tick 的 [sclArray] 仍从当前值开始，
     * 避免设置目标的那一刻就跳到目标尺寸。
     */
    private fun beginScaleTarget(scale: Float?, durationMs: Long, easingType: EasingType) {
        val fromX = baseScaleXAt(0f)
        val fromY = baseScaleYAt(0f)
        scaleFromX = fromX
        scaleFromY = fromY
        if (scale == null) {
            scaleToX = fromX
            scaleToY = fromY
        } else if (abs(fromX - fromY) <= 1e-6f) {
            scaleToX = scale
            scaleToY = scale
        } else {
            val maxFrom = max(abs(fromX), abs(fromY))
            val factor = if (maxFrom > 1e-6f) scale / maxFrom else 1f
            scaleToX = fromX * factor
            scaleToY = fromY * factor
        }
        scl.start = fromX
        scl.cur = fromX
        scl.tgt = scaleToX
        scaleEase.easing = easingType.curve
        scaleEase.startMs = engineMs
        scaleEase.durationMs = durationMs
        if (durationMs <= 0L) {
            applyScaleTarget()
            scaleEase.startMs = -1L
        }
    }

    /** 设置速度向量。 */
    fun setVelocity(velocity: Vec3) {
        this.velocity = velocity
        rotEase.active = false
        transEase.active = false
        offEase.active = false
        if (velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0) {
            posEase.startMs = -1L
        }
    }

    /** 设置旋转缓动：绕 [pivot] 将 [offset] 旋转到 [targetRot]。 */
    fun setRotation(pivot: Vec3, offset: Vec3, targetRot: DoubleArray, easingType: EasingType, durationMs: Long) {
        rotPivot = pivot
        off.cur = offset
        rot.start = rot.cur.copyOf()
        rot.tgt = targetRot.copyOf()
        rotEase.easing = easingType.curve
        rotEase.startMs = engineMs
        rotEase.durationMs = durationMs
        rotEase.active = true
        velocity = Vec3.ZERO
        posEase.startMs = -1L
    }

    /** 设置平移缓动：绕 [pivot] 在 [offset] 基础上叠加 [delta]。 */
    fun setTranslation(pivot: Vec3, offset: Vec3, delta: Vec3, easingType: EasingType, durationMs: Long) {
        rotPivot = pivot
        off.cur = offset
        trans.start = trans.cur
        trans.tgt = delta
        transEase.easing = easingType.curve
        transEase.startMs = engineMs
        transEase.durationMs = durationMs
        transEase.active = true
        velocity = Vec3.ZERO
        posEase.startMs = -1L
    }

    /** 设置偏移缓动：把未旋转偏移缓动到 [offset]，清零平移。 */
    fun setPositionSet(pivot: Vec3, offset: Vec3, easingType: EasingType, durationMs: Long) {
        rotPivot = pivot
        off.start = off.cur
        off.tgt = offset
        offEase.easing = easingType.curve
        offEase.startMs = engineMs
        offEase.durationMs = durationMs
        offEase.active = true
        trans.cur = Vec3.ZERO
        trans.start = Vec3.ZERO
        trans.tgt = Vec3.ZERO
        transEase.active = false
        velocity = Vec3.ZERO
        posEase.startMs = -1L
    }

    /** 当前速度向量。 */
    fun velocity(): Vec3 = velocity

    /**
     * 设置加速度（服务端权威力）与施加 tick 数：服务端与客户端按同一规则逐 tick 积分
     * （速度 += 加速度，再按速度位移），与 [setVelocity] 叠加。
     *
     * @param ticks >0 = 施加这么多 tick；<0 = 无限；0 = 清除
     */
    fun setAcceleration(acceleration: Vec3, ticks: Int) {
        this.acceleration = acceleration
        this.accelTicks = ticks
        rotEase.active = false
        transEase.active = false
        offEase.active = false
        if (accelTicks != 0) posEase.startMs = -1L
    }

    /** 是否还有未结束的施力。 */
    fun hasActiveForce(): Boolean = accelTicks != 0

    /** 设置位置的缓动目标。 */
    fun setPositionTarget(x: Double, y: Double, z: Double, easingType: EasingType, durationMs: Long) {
        velocity = Vec3.ZERO
        rotEase.active = false
        transEase.active = false
        offEase.active = false
        pos.start = pos.cur
        pos.tgt = Vec3(x, y, z)
        posEase.easing = easingType.curve
        posEase.startMs = engineMs
        posEase.durationMs = durationMs
        if (durationMs == 0L) snapNextSync = true
    }

    /** 立即跳变到目标位置。 */
    fun snapPosition(x: Double, y: Double, z: Double) {
        rotEase.active = false
        transEase.active = false
        offEase.active = false
        pos.cur = Vec3(x, y, z)
        pos.tgt = Vec3(x, y, z)
        posEase.startMs = -1L
        snapNextSync = true
    }

    /** 直接设置位置。 */
    fun setPositionDirect(position: Vec3) {
        rotEase.active = false
        transEase.active = false
        offEase.active = false
        prevX = pos.cur.x
        prevY = pos.cur.y
        prevZ = pos.cur.z
        pos.cur = position
        pos.tgt = position
        posEase.startMs = -1L
    }

    /** 直接设置颜色。 */
    fun setColorDirect(color: Color) {
        col.cur = color
        col.tgt = color
        colEase.startMs = -1L
    }

    /** 直接设置缩放（标量）。 */
    fun setScaleDirect(scale: Float) {
        scaleEase.startMs = -1L
        scaleFromX = scale
        scaleFromY = scale
        scaleToX = scale
        scaleToY = scale
        setScaleTriple(scale, scale, 1f)
        scl.start = scale
        scl.cur = scale
        scl.tgt = scale
    }

    /** 直接设置非均匀缩放（三分量数组 [sx, sy, sz]）；就地写入，避免每颗粒子每帧新分配数组。 */
    fun setScaleArrayDirect(scaleArray: FloatArray) {
        scaleEase.startMs = -1L
        setScaleTriple(scaleArray[0], scaleArray[1], scaleArray[2])
        scaleFromX = sclArray[0]
        scaleFromY = sclArray[1]
        scaleToX = sclArray[0]
        scaleToY = sclArray[1]
        scl.start = sclArray[0]
        scl.cur = sclArray[0]
        scl.tgt = sclArray[0]
    }

    /** 就地写三分量缩放，数组实例全程复用。 */
    private fun setScaleTriple(x: Float, y: Float, z: Float) {
        sclArray[0] = x
        sclArray[1] = y
        sclArray[2] = z
    }

    /**
     * 立即完成进行中的颜色/缩放缓动（cur = tgt 并停止计时）。
     * 零时长目标由 [ClientParticleEngine.updateParticle] 调用；等分批轮转落地会让后续缓动包读到旧起点。
     */
    fun finishColorScale(finishColor: Boolean = true, finishScale: Boolean = true) {
        if (finishColor && colEase.startMs >= 0L) {
            col.cur = col.tgt
            colEase.startMs = -1L
        }
        if (finishScale && scaleEase.startMs >= 0L) {
            applyScaleTarget()
            scaleEase.startMs = -1L
        }
    }

    /** 读取并清除「下一次同步应跳变」标记。 */
    fun consumeSnap(): Boolean {
        val s = snapNextSync
        snapNextSync = false
        return s
    }

    /** 缓动的目标位置。 */
    fun targetPosition(): Vec3 = pos.tgt

    /** 设置发光状态。 */
    fun setGlowing(glowing: Boolean) {
        this.glowing = glowing
    }

    /**
     * 设置发光光照等级，自动钳制到 [0, 15]。
     */
    fun setLightLevel(level: Int) {
        this.lightLevel = level.coerceIn(0, 15)
    }

    /**
     * 重设存活 tick 数，从此刻重新计。
     * @param lifetimeTicks 存活 tick 数；<=0 表示永久。按引擎 tick 计（关卡暂停时不流逝）
     */
    fun setLifetimeTicks(lifetimeTicks: Int) {
        this.lifetimeTicks = lifetimeTicks
        ticksAlive = 0
    }

    /** 每个引擎 tick 推进速度积分与缓动插值（[advanceEngine] 负责推进时钟，这里只用它算值）。 */
    fun tick() {
        val now = engineMs
        var posChanged = false

        // 施力：先改速度，再按速度位移
        if (accelTicks != 0) {
            velocity = velocity.add(acceleration)
            if (accelTicks > 0) accelTicks--
        }

        if (rotEase.active) {
            val elapsed = now - rotEase.startMs
            if (elapsed >= rotEase.durationMs) {
                rot.cur = rot.tgt.copyOf()
                rotEase.active = false
            } else {
                val e = rotEase.easing.evaluate(elapsed.toFloat() / rotEase.durationMs)
                for (i in 0..2) rot.cur[i] = lerp(rot.start[i], rot.tgt[i], e)
            }
            posChanged = true
        }
        if (transEase.active) {
            val elapsed = now - transEase.startMs
            if (elapsed >= transEase.durationMs) {
                trans.cur = trans.tgt
                transEase.active = false
            } else {
                trans.cur = lerpVec(trans.start, trans.tgt, transEase.easing.evaluate(elapsed.toFloat() / transEase.durationMs))
            }
            posChanged = true
        }
        if (offEase.active) {
            val elapsed = now - offEase.startMs
            if (elapsed >= offEase.durationMs) {
                off.cur = off.tgt
                offEase.active = false
            } else {
                off.cur = lerpVec(off.start, off.tgt, offEase.easing.evaluate(elapsed.toFloat() / offEase.durationMs))
            }
            posChanged = true
        }

        var posUpdated = false
        if (posChanged) {
            val p = rotPivot.add(trans.cur).add(rotateEuler(off.cur, rot.cur[0], rot.cur[1], rot.cur[2]))
            pos.cur = p
            pos.tgt = p
            posUpdated = true
        } else if (velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0) {
            pos.cur = pos.cur.add(velocity)
            pos.tgt = pos.tgt.add(velocity)
            posUpdated = true
        } else if (posEase.startMs >= 0L) {
            val elapsed = now - posEase.startMs
            if (elapsed >= posEase.durationMs) {
                pos.cur = pos.tgt
                posEase.startMs = -1L
            } else {
                pos.cur = lerpVec(pos.start, pos.tgt, posEase.easing.evaluate(elapsed.toFloat() / posEase.durationMs))
            }
            posUpdated = true
        }
        if (posUpdated) {
            prevX = pos.cur.x
            prevY = pos.cur.y
            prevZ = pos.cur.z
        }

        if (colEase.startMs >= 0L) {
            val elapsed = now - colEase.startMs
            if (elapsed >= colEase.durationMs) {
                col.cur = col.tgt
                colEase.startMs = -1L
            } else {
                val e = colEase.easing.evaluate(elapsed.toFloat() / colEase.durationMs)
                col.cur = col.start.lerp(col.tgt, e)
            }
        }

        if (scaleEase.startMs >= 0L) {
            val elapsed = now - scaleEase.startMs
            if (elapsed >= scaleEase.durationMs) {
                applyScaleTarget()
                scaleEase.startMs = -1L
            } else {
                val e = scaleEase.easing.evaluate(elapsed.toFloat() / scaleEase.durationMs)
                val x = lerp(scaleFromX, scaleToX, e)
                val y = lerp(scaleFromY, scaleToY, e)
                setScaleTriple(x, y, sclArray[2])
                scl.cur = x
            }
        }
    }

    /** 把缓动终点落到 [sclArray]，并让标量接口读到落地值。 */
    private fun applyScaleTarget() {
        setScaleTriple(scaleToX, scaleToY, sclArray[2])
        scl.cur = scaleToX
        scl.tgt = scaleToX
    }

    /** 按 X→Y→Z 顺序旋转偏移向量。 */
    private fun rotateEuler(v: Vec3, rx: Double, ry: Double, rz: Double): Vec3 {
        var r = v
        if (rx != 0.0) r = r.rotateAround(Vec3(1.0, 0.0, 0.0), rx)
        if (ry != 0.0) r = r.rotateAround(Vec3(0.0, 1.0, 0.0), ry)
        if (rz != 0.0) r = r.rotateAround(Vec3(0.0, 0.0, 1.0), rz)
        return r
    }

    companion object {
        private fun lerp(a: Double, b: Double, t: Float): Double = a + (b - a) * t

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun lerpVec(a: Vec3, b: Vec3, t: Float): Vec3 =
            Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    }
}
