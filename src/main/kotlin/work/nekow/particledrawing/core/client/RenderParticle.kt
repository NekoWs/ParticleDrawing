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
 * 渲染粒子：保存可视化状态并支持缓动过渡与速度积分。
 *
 * @param lightLevel 发光粒子向外发出的光照等级 (0-15)，仅在 glowing 为 true 时生效
 * @param lifetimeTicks 存活 tick 数；<=0 表示永久。按引擎 tick 计（关卡暂停时不流逝）
 * @param uv 贴图取景框；null = 纯白方块
 * @param lifeCurve 逐粒子寿命曲线（寿命内的颜色/尺寸乘数）；null = 恒定外观
 * @param prev 上一 tick 的位置，与 [position] 组成插值段；null = 直接出生
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
) {

    // 位置 / 颜色 / 缩放（直接缓动）
    private val pos = EaseVar(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)
    private val col = EaseVar(Color.BLACK, Color.BLACK, Color.BLACK)
    private val scl = EaseVar(0f, 0f, 0f)

    // 非均匀缩放三分量 [sx, sy, sz]；标量路径写 [s, s, 1]（粒子模型的 Z 恒为 1）
    private var sclArray: FloatArray = floatArrayOf(0f, 0f, 0f)

    // 旋转 / 平移 / 偏移（绕轴心独立缓动后叠加）
    private val rot = EaseVar(DoubleArray(3), DoubleArray(3), DoubleArray(3))
    private val trans = EaseVar(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)
    private val off = EaseVar(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)

    private val posEase = EaseState()
    private val colEase = EaseState()
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
        this.lightLevel = lightLevel.coerceIn(0, 15)
    }

    fun id(): UUID = id
    fun glowing(): Boolean = glowing
    fun lightLevel(): Int = lightLevel

    /** 生成后经过的 tick 数（引擎时钟：关卡暂停时不增长）。 */
    fun ageTicks(): Float = engineMs / 50.0f

    /**
     * 当前寿命曲线的乘数，按 [CurveChannel] 顺序写进 [out] = `[r, g, b, a, scale]`；
     * 没有曲线时返回 false 且不改动 [out]。
     */
    fun curveMultipliers(out: FloatArray): Boolean {
        if (lifeCurve == null) return false
        refreshCurve()
        out[0] = mulR
        out[1] = mulG
        out[2] = mulB
        out[3] = mulA
        out[4] = mulScale
        return true
    }

    /** 寿命曲线调制后的透明度（发光粒子的可见性判断用；无曲线时就是自身 alpha）。 */
    fun effectiveAlpha(): Float {
        if (lifeCurve == null) return col.cur.a
        refreshCurve()
        return col.cur.a * mulA
    }

    /** 一帧内只按当前时刻采样一次曲线。 */
    private fun refreshCurve() {
        val curve = lifeCurve ?: return
        val t = ageTicks()
        if (t == curveSampleTicks) return
        curveSampleTicks = t
        var r = 1f; var g = 1f; var b = 1f; var a = 1f; var s = 1f
        for (c in curve.curves) {
            val v = c.valueAt(t)
            when (c.channel) {
                CurveChannel.ALPHA -> a *= v
                CurveChannel.SCALE -> s *= v
                CurveChannel.RED -> r *= v
                CurveChannel.GREEN -> g *= v
                CurveChannel.BLUE -> b *= v
            }
        }
        mulR = r; mulG = g; mulB = b; mulA = a; mulScale = s
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
        col.start = col.cur
        scl.start = scl.cur
        pos.tgt = position
        col.tgt = color
        scl.tgt = scale
        setScaleScalar(scale)
        posEase.easing = easingType.curve
        colEase.easing = easingType.curve
        val now = engineMs
        posEase.startMs = now
        posEase.durationMs = durationMs
        colEase.startMs = now
        colEase.durationMs = durationMs
        if (durationMs == 0L) snapNextSync = true
    }

    /** 设置颜色与缩放的缓动目标（标量缩放）。 */
    fun setTargetColorScale(color: Color, scale: Float, easingType: EasingType, durationMs: Long) {
        col.start = col.cur
        scl.start = scl.cur
        col.tgt = color
        scl.tgt = scale
        setScaleScalar(scale)
        colEase.easing = easingType.curve
        colEase.startMs = engineMs
        colEase.durationMs = durationMs
        if (durationMs == 0L) snapNextSync = true
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
        scl.cur = scale
        scl.tgt = scale
        setScaleScalar(scale)
    }

    /** 直接设置非均匀缩放（三分量数组 [sx, sy, sz]）；就地写入，避免每颗粒子每帧新分配数组。 */
    fun setScaleArrayDirect(scaleArray: FloatArray) {
        setScaleTriple(scaleArray[0], scaleArray[1], scaleArray[2])
        scl.cur = sclArray[0]
        scl.tgt = sclArray[0]
    }

    /** 标量写法：X/Y 同值，Z 恒为 1。 */
    private fun setScaleScalar(scale: Float) {
        setScaleTriple(scale, scale, 1f)
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
    fun finishColorScale() {
        if (colEase.startMs >= 0L) {
            col.cur = col.tgt
            scl.cur = scl.tgt
            colEase.startMs = -1L
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
                scl.cur = scl.tgt
                colEase.startMs = -1L
            } else {
                val e = colEase.easing.evaluate(elapsed.toFloat() / colEase.durationMs)
                col.cur = col.start.lerp(col.tgt, e)
                scl.cur = lerp(scl.start, scl.tgt, e)
            }
        }
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
