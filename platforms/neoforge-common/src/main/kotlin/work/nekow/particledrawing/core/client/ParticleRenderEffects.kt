package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import work.nekow.particledrawing.api.SoftFade
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import work.nekow.particledrawing.api.VelocityStretch
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.render.ParticleDistanceLod
import work.nekow.particledrawing.render.RibbonHistory
import java.util.UUID
import kotlin.math.sqrt

/** 共用桥接粒子的附加外观。默认外观不分配状态。 */
internal class ParticleRenderEffects(private val visual: ResolvedVisual, start: Vec3) {
    val trail = visual.trailTicks.takeIf { it > 0 }?.let { RibbonHistory(it, start.toCore()) }
    val translucent: Boolean get() = visual.softFade > 0.0 || trail != null

    private val spin = if (visual.alignVelocity) DoubleArray(3) else null
    private var motionRotation: Quaternionf? = null
    private var hasMotionOrientation = false
    private var motionTick = Long.MIN_VALUE
    private var motionOrigin = start
    var stretchFactor = 1f
        private set

    private var softAlpha = 1f
    private var softStart = 1f
    private var lastSampleTick = Long.MIN_VALUE

    fun syncPosition(oldX: Double, oldY: Double, oldZ: Double, x: Double, y: Double, z: Double, snap: Boolean) {
        if (snap) {
            if (x != oldX || y != oldY || z != oldZ) resetPosition(x, y, z)
            return
        }
        if (spin == null) return
        val tick = ClientParticleEngine.tickSequence()
        if (motionTick != tick) {
            motionTick = tick
            motionOrigin = Vec3(oldX, oldY, oldZ)
        }
        updateMotion(x - motionOrigin.x, y - motionOrigin.y, z - motionOrigin.z)
    }

    private fun updateMotion(dx: Double, dy: Double, dz: Double) {
        if (spin == null) return
        val speed = sqrt(dx * dx + dy * dy + dz * dz)
        stretchFactor = VelocityStretch.lengthFactor(speed, visual.stretch).toFloat()
        if (!VelocityStretch.spinDegrees(dx, dy, dz, spin)) return
        val rotation = motionRotation ?: Quaternionf().also { motionRotation = it }
        rotation.rotationZYX(Math.toRadians(spin[2]).toFloat(), Math.toRadians(spin[1]).toFloat(), 0f)
        hasMotionOrientation = true
    }

    fun orientation(): Quaternionf? = if (hasMotionOrientation) motionRotation else null

    private fun resetPosition(x: Double, y: Double, z: Double) {
        motionTick = Long.MIN_VALUE
        trail?.reset(CoreVec3(x, y, z))
        lastSampleTick = Long.MIN_VALUE
        updateMotion(0.0, 0.0, 0.0)
        hasMotionOrientation = false
    }

    fun record(x: Double, y: Double, z: Double) {
        trail?.record(CoreVec3(x, y, z))
    }

    fun alpha(x: Double, y: Double, z: Double, partialTick: Float): Float {
        if (visual.softFade <= 0.0) return 1f
        val tick = ClientParticleEngine.tickSequence()
        if (lastSampleTick != tick) {
            val next = SoftFade.alpha(ClientParticleWorld, CoreVec3(x, y, z), visual.softFade)
            softStart = if (lastSampleTick == tick - 1) softAlpha else next
            softAlpha = next
            lastSampleTick = tick
        }
        return softStart + (softAlpha - softStart) * partialTick
    }

    fun bounds(x: Double, y: Double, z: Double, half: Float): AABB {
        var minX = x
        var minY = y
        var minZ = z
        var maxX = x
        var maxY = y
        var maxZ = z
        if (trail != null) for (i in 0 until trail.size) {
            val position = trail.sample(i)
            minX = minOf(minX, position.x)
            minY = minOf(minY, position.y)
            minZ = minOf(minZ, position.z)
            maxX = maxOf(maxX, position.x)
            maxY = maxOf(maxY, position.y)
            maxZ = maxOf(maxZ, position.z)
        }
        return AABB(minX - half, minY - half, minZ - half, maxX + half, maxY + half, maxZ + half)
    }

    companion object {
        fun visible(id: UUID, camera: Vec3, x: Double, y: Double, z: Double): Boolean {
            val distance = ParticleDrawingConfig.CLIENT.particleLodDistance.get()
            if (distance <= 0.0) return true
            val bits = id.mostSignificantBits xor id.leastSignificantBits
            val rank = (bits ushr 11) / 9007199254740992.0
            return ParticleDistanceLod.visible(camera.distanceToSqr(x, y, z), distance, rank)
        }
    }
}
