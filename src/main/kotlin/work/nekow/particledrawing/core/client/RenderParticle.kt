package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.core.easing.EasingCurve
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

/**
 * 渲染粒子，保存粒子的所有可视化状态并支持缓动过渡。
 * 通过 setTarget 设置目标值后，每帧 tick 会根据缓动曲线自动插值。
 *
 * @param id 粒子唯一标识符
 * @param style 粒子样式
 * @param position 初始位置
 * @param color 初始颜色
 * @param scale 初始缩放
 * @param glowing 是否发光
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
    private var easing: EasingCurve
    private var easeDurationNs: Long
    private var easeStartTime: Long
    private var snapNextSync: Boolean = false

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
        easing = LINEAR
        easeDurationNs = 0
        easeStartTime = 0
    }

    fun id(): UUID = id
    fun glowing(): Boolean = glowing

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

    /**
     * 设置缓动过渡的目标值。
     * @param position 目标位置
     * @param color 目标颜色
     * @param scale 目标缩放
     * @param easingType 缓动类型
     * @param durationMs 过渡持续时间（毫秒）
     */
    fun setTarget(position: Vec3, color: Color, scale: Float, easingType: EasingType, durationMs: Long) {
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
        easeDurationNs = durationMs * 1_000_000L
        easeStartTime = System.nanoTime()
        if (durationMs == 0L) snapNextSync = true
    }

    /**
     * 直接设置位置，不经过缓动。
     * @param position 目标位置
     */
    fun setPositionDirect(position: Vec3) {
        curX = position.x; tgtX = position.x
        curY = position.y; tgtY = position.y
        curZ = position.z; tgtZ = position.z
        easeStartTime = 0
    }

    /**
     * 直接设置颜色，不经过缓动。
     * @param color 目标颜色
     */
    fun setColorDirect(color: Color) {
        curR = color.r; tgtR = color.r
        curG = color.g; tgtG = color.g
        curB = color.b; tgtB = color.b
        curA = color.a; tgtA = color.a
        easeStartTime = 0
    }

    /** 直接设置缩放。 */
    fun setScaleDirect(scale: Float) {
        curScale = scale; tgtScale = scale
    }

    fun isSnapSync(): Boolean = snapNextSync

    /** 返回缓动的目标位置（用于绝对坐标变换，避免插值漂移） */
    fun targetPosition(): Vec3 = Vec3(tgtX, tgtY, tgtZ)

    /**
     * 设置发光状态。
     * @param glowing 是否发光
     */
    fun setGlowing(glowing: Boolean) {
        this.glowing = glowing
    }

    /**
     * 设置存活时间。
     * @param lifetimeMs 存活时间（毫秒），0 表示永久
     */
    fun setLifetime(lifetimeMs: Long) {
        deathTime = if (lifetimeMs > 0) System.nanoTime() + lifetimeMs * 1_000_000L else 0
    }

    /**
     * 每帧更新：根据缓动曲线插值当前位置、颜色和缩放。
     */
    fun tick() {
        if (easeStartTime == 0L) return

        val now = System.nanoTime()
        val elapsed = now - easeStartTime

        if (elapsed >= easeDurationNs) {
            curX = tgtX; curY = tgtY; curZ = tgtZ
            curR = tgtR; curG = tgtG; curB = tgtB; curA = tgtA
            curScale = tgtScale
            easeStartTime = 0
            snapNextSync = false
            return
        }

        val t = elapsed.toFloat() / easeDurationNs
        val easedT = easing.evaluate(t)

        curX = lerp(startX, tgtX, easedT)
        curY = lerp(startY, tgtY, easedT)
        curZ = lerp(startZ, tgtZ, easedT)
        curR = lerp(startR, tgtR, easedT)
        curG = lerp(startG, tgtG, easedT)
        curB = lerp(startB, tgtB, easedT)
        curA = lerp(startA, tgtA, easedT)
        curScale = lerp(startScale, tgtScale, easedT)
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
