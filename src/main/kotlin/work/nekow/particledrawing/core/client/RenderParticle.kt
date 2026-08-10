package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.core.easing.EasingCurve
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID

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

    private var deathTime: Long
    private var easing: EasingCurve
    private var easeDurationNs: Long
    private var easeStartTime: Long

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

    fun setTarget(position: Vec3, color: Color, scale: Float, easingType: EasingType, durationMs: Long) {
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
    }

    fun setPositionDirect(position: Vec3) {
        curX = position.x; tgtX = position.x
        curY = position.y; tgtY = position.y
        curZ = position.z; tgtZ = position.z
        easeStartTime = 0
    }

    fun setColorDirect(color: Color) {
        curR = color.r; tgtR = color.r
        curG = color.g; tgtG = color.g
        curB = color.b; tgtB = color.b
        curA = color.a; tgtA = color.a
        easeStartTime = 0
    }

    fun setGlowing(glowing: Boolean) {
        this.glowing = glowing
    }

    fun setLifetime(lifetimeMs: Long) {
        deathTime = if (lifetimeMs > 0) System.nanoTime() + lifetimeMs * 1_000_000L else 0
    }

    fun tick() {
        if (easeStartTime == 0L) return

        val now = System.nanoTime()
        val elapsed = now - easeStartTime

        if (elapsed >= easeDurationNs) {
            curX = tgtX; curY = tgtY; curZ = tgtZ
            curR = tgtR; curG = tgtG; curB = tgtB; curA = tgtA
            curScale = tgtScale
            easeStartTime = 0
            return
        }

        val t = elapsed.toFloat() / easeDurationNs
        val easedT = easing.evaluate(t)

        curX = lerp(curX, tgtX, easedT)
        curY = lerp(curY, tgtY, easedT)
        curZ = lerp(curZ, tgtZ, easedT)
        curR = lerp(curR, tgtR, easedT)
        curG = lerp(curG, tgtG, easedT)
        curB = lerp(curB, tgtB, easedT)
        curA = lerp(curA, tgtA, easedT)
        curScale = lerp(curScale, tgtScale, easedT)
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
