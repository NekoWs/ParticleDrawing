package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.core.easing.EasingCurve
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.motion.rotateAround
import java.util.UUID

/**
 * 渲染粒子，保存粒子的可视化状态并支持缓动过渡与速度积分。
 *
 * @param id 粒子唯一标识符
 * @param style 粒子样式
 * @param position 初始位置
 * @param color 初始颜色
 * @param scale 初始缩放
 * @param glowing 是否发光
 * @param lightLevel 发光粒子向外发出的光照等级 (0-15)，仅在 glowing 为 true 时生效
 * @param lifetimeMs 存活时间（毫秒），0 表示永久
 */
@Suppress("unused")
class RenderParticle(
    private val id: UUID,
    val style: ParticleStyle,
    position: Vec3,
    color: Color,
    scale: Float,
    private var glowing: Boolean,
    private var lightLevel: Int,
    lifetimeMs: Long
) {

    private var curX: Double
    private var curY: Double
    private var curZ: Double
    private var tgtX: Double
    private var tgtY: Double
    private var tgtZ: Double

    private var curR: Float
    private var curG: Float
    private var curB: Float
    private var curA: Float
    private var tgtR: Float
    private var tgtG: Float
    private var tgtB: Float
    private var tgtA: Float

    private var curScale: Float
    private var tgtScale: Float

    private var startX: Double = 0.0
    private var startY: Double = 0.0
    private var startZ: Double = 0.0
    private var startR: Float = 0f
    private var startG: Float = 0f
    private var startB: Float = 0f
    private var startA: Float = 0f
    private var startScale: Float = 0f

    private var deathTime: Long

    // 位置缓动与颜色/缩放缓动使用独立计时器
    private var easing: EasingCurve
    private var posEaseStartTime: Long = 0L
    private var posEaseDurationNs: Long = 0L
    private var colEaseStartTime: Long = 0L
    private var colEaseDurationNs: Long = 0L
    private var snapNextSync: Boolean = false

    // 速度向量（blocks/tick）
    private var velocity: Vec3 = Vec3.ZERO

    // 旋转插值状态（绕轴心做圆弧运动，避免线性插值位置导致的收缩失真）
    private var rotActive = false
    private var rotPivot = Vec3.ZERO
    private var startRot = DoubleArray(3)
    private var tgtRot = DoubleArray(3)
    private var curRot = DoubleArray(3)
    private var rotEaseStartTime = 0L
    private var rotEaseDurationNs = 0L
    private var rotEasing: EasingCurve = LINEAR

    // 平移插值状态（组 op 位置增量，与旋转独立缓动后叠加）
    private var translateActive = false
    private var curTranslate = Vec3.ZERO
    private var tgtTranslate = Vec3.ZERO
    private var startTranslate = Vec3.ZERO
    private var translateEaseStartTime = 0L
    private var translateEaseDurationNs = 0L
    private var translateEasing: EasingCurve = LINEAR

    // 偏移插值状态（相对轴心的未旋转位置；op 模式恒为 base-pivot，set 模式位置轨道会改变它）
    private var offsetActive = false
    private var curOffset = Vec3.ZERO
    private var tgtOffset = Vec3.ZERO
    private var startOffset = Vec3.ZERO
    private var offsetEaseStartTime = 0L
    private var offsetEaseDurationNs = 0L
    private var offsetEasing: EasingCurve = LINEAR

    init {
        curX = position.x; tgtX = position.x
        curY = position.y; tgtY = position.y
        curZ = position.z; tgtZ = position.z
        curR = color.r; tgtR = color.r
        curG = color.g; tgtG = color.g
        curB = color.b; tgtB = color.b
        curA = color.a; tgtA = color.a
        curScale = scale; tgtScale = scale
        deathTime = if (lifetimeMs > 0) System.nanoTime() + lifetimeMs * 1_000_000L else 0
        this.lightLevel = lightLevel.coerceIn(0, 15)
        easing = LINEAR
    }

    fun id(): UUID = id
    fun glowing(): Boolean = glowing
    fun lightLevel(): Int = lightLevel

    fun x(): Double = curX
    fun y(): Double = curY
    fun z(): Double = curZ
    fun r(): Float = curR
    fun g(): Float = curG
    fun b(): Float = curB
    fun a(): Float = curA
    fun scale(): Float = curScale

    fun isAlive(): Boolean {
        return deathTime == 0L || System.nanoTime() < deathTime
    }

    fun isDead(): Boolean = !isAlive()

    /** 设置位置、颜色与缩放的缓动目标。 */
    fun setTarget(position: Vec3, color: Color, scale: Float, easingType: EasingType, durationMs: Long) {
        velocity = Vec3.ZERO
        rotActive = false
        translateActive = false
        offsetActive = false
        startX = curX
        startY = curY
        startZ = curZ
        startR = curR
        startG = curG
        startB = curB
        startA = curA
        startScale = curScale

        tgtX = position.x
        tgtY = position.y
        tgtZ = position.z
        tgtR = color.r
        tgtG = color.g
        tgtB = color.b
        tgtA = color.a
        tgtScale = scale
        easing = easingType.curve
        val now = System.nanoTime()
        posEaseStartTime = now
        posEaseDurationNs = durationMs * 1_000_000L
        colEaseStartTime = now
        colEaseDurationNs = durationMs * 1_000_000L
        if (durationMs == 0L) snapNextSync = true
    }

    /** 仅设置颜色与缩放的缓动目标。 */
    fun setTargetColorScale(color: Color, scale: Float, easingType: EasingType, durationMs: Long) {
        startR = curR
        startG = curG
        startB = curB
        startA = curA
        startScale = curScale

        tgtR = color.r
        tgtG = color.g
        tgtB = color.b
        tgtA = color.a
        tgtScale = scale
        easing = easingType.curve
        colEaseStartTime = System.nanoTime()
        colEaseDurationNs = durationMs * 1_000_000L
        if (durationMs == 0L) snapNextSync = true
    }

    /** 设置速度向量（blocks/tick）。 */
    fun setVelocity(velocity: Vec3) {
        this.velocity = velocity
        rotActive = false
        translateActive = false
        offsetActive = false
        if (velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0) {
            posEaseStartTime = 0L
        }
    }

    /**
     * 设置旋转目标：绕 [pivot] 轴心将偏移 [offset] 旋转到 [targetRot] 欧拉角（X→Y→Z）。
     */
    fun setRotation(pivot: Vec3, offset: Vec3, targetRot: DoubleArray, easingType: EasingType, durationMs: Long) {
        rotPivot = pivot
        curOffset = offset
        startRot = curRot.copyOf()
        tgtRot = targetRot.copyOf()
        rotEasing = easingType.curve
        rotEaseStartTime = System.nanoTime()
        rotEaseDurationNs = durationMs * 1_000_000L
        rotActive = true
        velocity = Vec3.ZERO
        posEaseStartTime = 0L
    }

    /**
     * 设置平移目标（组 op 位置增量）：绕 [pivot] 轴心，在 [offset] 基础上叠加 [delta]。
     */
    fun setTranslation(pivot: Vec3, offset: Vec3, delta: Vec3, easingType: EasingType, durationMs: Long) {
        rotPivot = pivot
        curOffset = offset
        startTranslate = curTranslate
        tgtTranslate = delta
        translateEasing = easingType.curve
        translateEaseStartTime = System.nanoTime()
        translateEaseDurationNs = durationMs * 1_000_000L
        translateActive = true
        velocity = Vec3.ZERO
        posEaseStartTime = 0L
    }

    /**
     * 设置位置目标（组 set 位置轨道）：把未旋转偏移 [offset] 缓动到新值，保留旋转、清零平移增量。
     */
    fun setPositionSet(pivot: Vec3, offset: Vec3, easingType: EasingType, durationMs: Long) {
        rotPivot = pivot
        startOffset = curOffset
        tgtOffset = offset
        offsetEasing = easingType.curve
        offsetEaseStartTime = System.nanoTime()
        offsetEaseDurationNs = durationMs * 1_000_000L
        offsetActive = true
        curTranslate = Vec3.ZERO
        startTranslate = Vec3.ZERO
        tgtTranslate = Vec3.ZERO
        translateActive = false
        velocity = Vec3.ZERO
        posEaseStartTime = 0L
    }

    /** 当前速度向量。 */
    fun velocity(): Vec3 = velocity

    /** 设置位置的缓动目标。 */
    fun setPositionTarget(x: Double, y: Double, z: Double, easingType: EasingType, durationMs: Long) {
        velocity = Vec3.ZERO
        rotActive = false
        translateActive = false
        offsetActive = false
        startX = curX
        startY = curY
        startZ = curZ
        tgtX = x
        tgtY = y
        tgtZ = z
        easing = easingType.curve
        posEaseStartTime = System.nanoTime()
        posEaseDurationNs = durationMs * 1_000_000L
        if (durationMs == 0L) snapNextSync = true
    }

    /** 立即跳变到目标位置。 */
    fun snapPosition(x: Double, y: Double, z: Double) {
        rotActive = false
        translateActive = false
        offsetActive = false
        curX = x; tgtX = x
        curY = y; tgtY = y
        curZ = z; tgtZ = z
        posEaseStartTime = 0L
        snapNextSync = true
    }

    /** 直接设置位置，不经过缓动。 */
    fun setPositionDirect(position: Vec3) {
        rotActive = false
        translateActive = false
        offsetActive = false
        curX = position.x; tgtX = position.x
        curY = position.y; tgtY = position.y
        curZ = position.z; tgtZ = position.z
        posEaseStartTime = 0L
    }

    /**
     * 直接设置颜色，不经过缓动。
     */
    fun setColorDirect(color: Color) {
        curR = color.r; tgtR = color.r
        curG = color.g; tgtG = color.g
        curB = color.b; tgtB = color.b
        curA = color.a; tgtA = color.a
        colEaseStartTime = 0L
    }

    /** 直接设置缩放。 */
    fun setScaleDirect(scale: Float) {
        curScale = scale; tgtScale = scale
    }

    /** 读取并清除「下一次同步应跳变」标记。 */
    fun consumeSnap(): Boolean {
        val s = snapNextSync
        snapNextSync = false
        return s
    }

    /** 返回缓动的目标位置。 */
    fun targetPosition(): Vec3 = Vec3(tgtX, tgtY, tgtZ)

    /**
     * 设置发光状态。
     * @param glowing 是否发光
     */
    fun setGlowing(glowing: Boolean) {
        this.glowing = glowing
    }

    /**
     * 设置发光粒子向外发出的光照等级 (0-15)。
     * @param level 光照等级，自动钳制到 [0, 15]
     */
    fun setLightLevel(level: Int) {
        this.lightLevel = level.coerceIn(0, 15)
    }

    /**
     * 设置存活时间。
     * @param lifetimeMs 存活时间（毫秒），0 表示永久
     */
    fun setLifetime(lifetimeMs: Long) {
        deathTime = if (lifetimeMs > 0) System.nanoTime() + lifetimeMs * 1_000_000L else 0
    }

    /** 每帧更新：推进速度积分与缓动插值。 */
    fun tick() {
        val now = System.nanoTime()

        if (rotActive) {
            val elapsed = now - rotEaseStartTime
            if (elapsed >= rotEaseDurationNs) {
                curRot = tgtRot.copyOf()
                rotActive = false
            } else {
                val t = elapsed.toFloat() / rotEaseDurationNs
                val e = rotEasing.evaluate(t)
                for (i in 0..2) curRot[i] = lerp(startRot[i], tgtRot[i], e)
            }
        }
        if (translateActive) {
            val elapsed = now - translateEaseStartTime
            if (elapsed >= translateEaseDurationNs) {
                curTranslate = tgtTranslate
                translateActive = false
            } else {
                val t = elapsed.toFloat() / translateEaseDurationNs
                val e = translateEasing.evaluate(t)
                curTranslate = lerpVec(startTranslate, tgtTranslate, e)
            }
        }
        if (offsetActive) {
            val elapsed = now - offsetEaseStartTime
            if (elapsed >= offsetEaseDurationNs) {
                curOffset = tgtOffset
                offsetActive = false
            } else {
                val t = elapsed.toFloat() / offsetEaseDurationNs
                val e = offsetEasing.evaluate(t)
                curOffset = lerpVec(startOffset, tgtOffset, e)
            }
        }

        if (rotActive || translateActive || offsetActive) {
            val pos = rotPivot.add(curTranslate).add(rotateEuler(curOffset, curRot[0], curRot[1], curRot[2]))
            curX = pos.x; tgtX = pos.x
            curY = pos.y; tgtY = pos.y
            curZ = pos.z; tgtZ = pos.z
        } else if (velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0) {
            curX += velocity.x; tgtX += velocity.x
            curY += velocity.y; tgtY += velocity.y
            curZ += velocity.z; tgtZ += velocity.z
        } else if (posEaseStartTime != 0L) {
            val elapsed = now - posEaseStartTime
            if (elapsed >= posEaseDurationNs) {
                curX = tgtX; curY = tgtY; curZ = tgtZ
                posEaseStartTime = 0L
            } else {
                val t = elapsed.toFloat() / posEaseDurationNs
                val e = easing.evaluate(t)
                curX = lerp(startX, tgtX, e)
                curY = lerp(startY, tgtY, e)
                curZ = lerp(startZ, tgtZ, e)
            }
        }

        if (colEaseStartTime != 0L) {
            val elapsed = now - colEaseStartTime
            if (elapsed >= colEaseDurationNs) {
                curR = tgtR; curG = tgtG; curB = tgtB; curA = tgtA
                curScale = tgtScale
                colEaseStartTime = 0L
            } else {
                val t = elapsed.toFloat() / colEaseDurationNs
                val e = easing.evaluate(t)
                curR = lerp(startR, tgtR, e)
                curG = lerp(startG, tgtG, e)
                curB = lerp(startB, tgtB, e)
                curA = lerp(startA, tgtA, e)
                curScale = lerp(startScale, tgtScale, e)
            }
        }
    }

    /** 按 X→Y→Z 顺序旋转偏移向量（与编辑器一致）。 */
    private fun rotateEuler(v: Vec3, rx: Double, ry: Double, rz: Double): Vec3 {
        var r = v
        if (rx != 0.0) r = r.rotateAround(Vec3(1.0, 0.0, 0.0), rx)
        if (ry != 0.0) r = r.rotateAround(Vec3(0.0, 1.0, 0.0), ry)
        if (rz != 0.0) r = r.rotateAround(Vec3(0.0, 0.0, 1.0), rz)
        return r
    }

    companion object {
        private val LINEAR = EasingCurve(0.0, 0.0, 1.0, 1.0)

        private fun lerp(a: Double, b: Double, t: Float): Double {
            return a + (b - a) * t
        }

        private fun lerp(a: Float, b: Float, t: Float): Float {
            return a + (b - a) * t
        }

        private fun lerpVec(a: Vec3, b: Vec3, t: Float): Vec3 {
            return Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
            )
        }
    }
}
