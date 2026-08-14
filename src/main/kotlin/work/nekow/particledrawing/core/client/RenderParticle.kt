package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.core.easing.EasingCurve
import work.nekow.particledrawing.core.easing.EasingType
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
        if (velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0) {
            posEaseStartTime = 0L
        }
    }

    /** 当前速度向量。 */
    fun velocity(): Vec3 = velocity

    /** 设置位置的缓动目标。 */
    fun setPositionTarget(x: Double, y: Double, z: Double, easingType: EasingType, durationMs: Long) {
        velocity = Vec3.ZERO
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
        curX = x; tgtX = x
        curY = y; tgtY = y
        curZ = z; tgtZ = z
        posEaseStartTime = 0L
        snapNextSync = true
    }

    /** 直接设置位置，不经过缓动。 */
    fun setPositionDirect(position: Vec3) {
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

        if (velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0) {
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

    companion object {
        private val LINEAR = EasingCurve(0.0, 0.0, 1.0, 1.0)

        private fun lerp(a: Double, b: Double, t: Float): Double {
            return a + (b - a) * t
        }

        private fun lerp(a: Float, b: Float, t: Float): Float {
            return a + (b - a) * t
        }
    }
}
