package work.nekow.particledrawing.lighting

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.LightSample
import work.nekow.particledrawing.config.ParticleDrawingConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object SpotlightBeams {
    private const val MAX_BEAMS = 32
    private const val SIDES = 20
    private val circle = Array(SIDES) { i ->
        val angle = 2.0 * PI * i / SIDES
        Pair(cos(angle), sin(angle))
    }

    internal class Beam(
        val light: LightSample, val origin: Vec3, val axis: Vec3, val length: Double,
    )

    fun collect(camera: Vec3): List<Beam> {
        if (!DynamicLightManager.hasActiveLights() || !ParticleDrawingConfig.CLIENT.showSpotlightBeams.get()) {
            return emptyList()
        }
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return emptyList()
        val player = minecraft.player ?: return emptyList()
        return DynamicLightManager.lightSnapshot().asSequence()
            .filter { it.shape?.isSpot == true }
            .sortedBy { camera.distanceToSqr(it.x, it.y, it.z) }
            .take(MAX_BEAMS)
            .mapNotNull { light ->
                val origin = Vec3(light.x, light.y, light.z)
                val (x, y, z) = checkNotNull(light.shape).direction()
                val axis = Vec3(x, y, z)
                val hit = level.clip(ClipContext(
                    origin, origin.add(axis.scale(light.range)), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player,
                ))
                val length = if (hit.type == HitResult.Type.MISS) light.range else hit.location.distanceTo(origin)
                if (length <= 0.05) null else Beam(light, origin.subtract(camera), axis, length)
            }.toList()
    }

    fun draw(beams: List<Beam>, pose: PoseStack.Pose, vertices: VertexConsumer) {
        for (beam in beams) {
            val shape = checkNotNull(beam.light.shape)
            val axis = beam.axis
            val reference = if (kotlin.math.abs(axis.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
            val right = axis.cross(reference).normalize()
            val up = right.cross(axis)
            // 两层淡雾共用同一尖端，沿光轴逐渐消散。
            for (angle in doubleArrayOf(shape.effectiveInnerDeg * 0.75, shape.outerDeg)) {
                val theta = Math.toRadians(angle)
                val axial = cos(theta)
                val radial = sin(theta)
                val opacity = (beam.light.intensity * 2.3).toInt()
                fun vertex(i: Int, distance: Double, alpha: Int) {
                    val (c, s) = circle[i]
                    val lateralX = right.x * c + up.x * s
                    val lateralY = right.y * c + up.y * s
                    val lateralZ = right.z * c + up.z * s
                    val origin = beam.origin
                    vertices.addVertex(pose,
                        (origin.x + distance * (axial * axis.x + radial * lateralX)).toFloat(),
                        (origin.y + distance * (axial * axis.y + radial * lateralY)).toFloat(),
                        (origin.z + distance * (axial * axis.z + radial * lateralZ)).toFloat(),
                    ).setColor((alpha shl 24) or beam.light.rgb)
                }
                // 射线长度以球形照射半径为上限；朝轴的光线遇到方块时缩短。
                val end = if (axial > 0.0) minOf(beam.light.range, beam.length / axial) else beam.light.range
                for (i in 0 until SIDES) {
                    val next = (i + 1) % SIDES
                    vertex(i, 0.0, opacity)
                    vertex(next, 0.0, opacity)
                    vertex(next, end, 0)
                    vertex(i, end, 0)
                }
            }
        }
    }
}
