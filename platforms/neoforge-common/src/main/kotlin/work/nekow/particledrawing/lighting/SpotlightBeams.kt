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
import kotlin.math.min
import kotlin.math.sin

internal object SpotlightBeams {
    private const val MAX_BEAMS = 32
    private const val SIDES = 20
    private const val SEGMENTS = 8

    /** 光锥只画最亮的芯：锥角再大也不超过这个角度，免得远处张成一大片白雾。 */
    private const val MAX_VISUAL_DEG = 24.0

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
                val hit = level.clip(
                    ClipContext(
                        origin, origin.add(axis.scale(light.range)), ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, player,
                    ),
                )
                val length = if (hit.type == HitResult.Type.MISS) light.range else hit.location.distanceTo(origin)
                if (length <= 0.05) null else Beam(light, origin.subtract(camera), axis, length)
            }.toList()
    }

    /**
     * 画柔和的光锥：沿光轴分 [SEGMENTS] 段，亮度在锥尖淡入、朝末端平方衰减，正反两面各画一遍。
     * 锥角取内锥（满亮区）并压到 [MAX_VISUAL_DEG] 以内，避免宽锥变成一团白雾。
     */
    fun draw(beams: List<Beam>, pose: PoseStack.Pose, vertices: VertexConsumer) {
        for (beam in beams) {
            val shape = checkNotNull(beam.light.shape)
            val axis = beam.axis
            val reference = if (kotlin.math.abs(axis.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
            val right = axis.cross(reference).normalize()
            val up = right.cross(axis)

            val degrees = shape.effectiveInnerDeg.takeIf { it > 0.0 } ?: shape.outerDeg
            val theta = Math.toRadians(min(degrees, MAX_VISUAL_DEG))
            val axial = cos(theta)
            val radial = sin(theta)
            val length = min(beam.length, beam.light.range)
            val baseAlpha = (beam.light.intensity / 15.0 * 0.12 * 255.0).toInt().coerceIn(1, 32)

            for (segment in 0 until SEGMENTS) {
                val t0 = segment.toDouble() / SEGMENTS
                val t1 = (segment + 1).toDouble() / SEGMENTS
                val a0 = alphaAt(baseAlpha, t0)
                val a1 = alphaAt(baseAlpha, t1)
                if (a0 <= 0 && a1 <= 0) continue
                for (i in 0 until SIDES) {
                    val next = (i + 1) % SIDES
                    val distance0 = t0 * length / axial
                    val distance1 = t1 * length / axial
                    // 正反两遍，渲染类型按剔除方向丢掉一面时仍有可见面。
                    quad(
                        vertices, pose, beam, axis, right, up, i, next,
                        distance0, distance1, radial, baseAlpha, a0, a1,
                    )
                    quad(
                        vertices, pose, beam, axis, right, up, next, i,
                        distance0, distance1, radial, baseAlpha, a0, a1,
                    )
                }
            }
        }
    }

    private fun quad(
        vertices: VertexConsumer, pose: PoseStack.Pose, beam: Beam,
        axis: Vec3, right: Vec3, up: Vec3,
        i: Int, j: Int, distance0: Double, distance1: Double,
        radial: Double, baseAlpha: Int, alpha0: Int, alpha1: Int,
    ) {
        vertex(vertices, pose, beam, axis, right, up, i, distance0, radial, if (alpha0 > 0) alpha0 else 1)
        vertex(vertices, pose, beam, axis, right, up, j, distance0, radial, if (alpha0 > 0) alpha0 else 1)
        vertex(vertices, pose, beam, axis, right, up, j, distance1, radial, if (alpha1 > 0) alpha1 else 1)
        vertex(vertices, pose, beam, axis, right, up, i, distance1, radial, if (alpha1 > 0) alpha1 else 1)
    }

    /** 沿长度的亮度曲线：锥尖淡入，末端平方衰减。 */
    private fun alphaAt(baseAlpha: Int, t: Double): Int {
        val fadeIn = ((t - 0.02) / 0.08).coerceIn(0.0, 1.0)
        val remaining = (1.0 - t).coerceIn(0.0, 1.0)
        return (baseAlpha * fadeIn * remaining * remaining).toInt().coerceIn(0, 255)
    }

    private fun vertex(
        vertices: VertexConsumer, pose: PoseStack.Pose, beam: Beam,
        axis: Vec3, right: Vec3, up: Vec3,
        index: Int, distance: Double, radial: Double, alpha: Int,
    ) {
        val (c, s) = circle[index]
        val lateralX = right.x * c + up.x * s
        val lateralY = right.y * c + up.y * s
        val lateralZ = right.z * c + up.z * s
        val origin = beam.origin
        vertices.addVertex(
            pose,
            (origin.x + distance * (axis.x + radial * lateralX)).toFloat(),
            (origin.y + distance * (axis.y + radial * lateralY)).toFloat(),
            (origin.z + distance * (axis.z + radial * lateralZ)).toFloat(),
        ).setColor((alpha shl 24) or beam.light.rgb)
    }
}
