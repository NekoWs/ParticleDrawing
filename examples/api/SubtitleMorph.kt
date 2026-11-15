package examples

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.morph.MorphEasing
import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphWobble
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.api.ParticleMorphSlot
import work.nekow.particledrawing.api.ParticleSpawnSpec
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual
import java.awt.Font
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 字幕位于世界 XY 平面，从 +Z 一侧观看。各方法在服务端主线程调用。 */
class SubtitleMorph private constructor(
    val group: ParticleGroup,
    private val center: Vec3,
    private val font: Font,
) {
    enum class Target { FIRST_CAPTION, SECOND_CAPTION, CUBE, SPHERE }

    fun show(target: Target): SubtitleMorph = apply {
        group.morphSlots(slots(target, center, font), transition)
    }

    fun remove() = group.remove()

    companion object {
        private const val captionHeight = 2.4
        private const val captionSize = 0.82
        private const val cubeSide = 11
        private const val count = cubeSide * cubeSide * cubeSide - (cubeSide - 2) * (cubeSide - 2) * (cubeSide - 2)
        private val transition = MorphOptions(
            ticks = 48,
            staggerTicks = 8,
            easing = MorphEasing.IN_OUT_CUBIC,
            wobble = MorphWobble.VORTEX,
            wobbleStrength = 0.18,
            maxSpeed = 0.40,
        )

        @JvmStatic
        @JvmOverloads
        fun spawn(manager: ParticleManager, center: Vec3, font: Font = Font(Font.SANS_SERIF, Font.BOLD, 1)): SubtitleMorph {
            val appearance = ParticleVisual().style(ParticleStyle.SOFT_DOT).billboard(false)
            val particles = manager.spawnAll(slots(Target.FIRST_CAPTION, center, font).map { slot ->
                ParticleSpawnSpec().position(slot.position).visual(appearance)
                    .color(Color.ofInt(136, 229, 255, 255)).scale(captionSize.toFloat()).glowing(true).lightLevel(0)
            })
            if (particles.any { it == null }) {
                particles.forEach { it?.remove() }
                error("粒子容量不足，字幕需要 $count 颗粒子")
            }
            val group = manager.createGroup(center)
            particles.forEach(group::add)
            return SubtitleMorph(group, center, font)
        }

        private fun slots(target: Target, center: Vec3, font: Font): List<ParticleMorphSlot> = when (target) {
            Target.FIRST_CAPTION -> caption("粒子字幕", center, font)
            Target.SECOND_CAPTION -> caption("变换形状", center, font)
            Target.CUBE -> cube(center).map { point ->
                val yaw = 54.0 + 8.0 * (point.x - center.x)
                val size = 1.8 + 0.25 * (point.y - center.y)
                ParticleMorphSlot(point, yaw, -32.0, size)
            }
            Target.SPHERE -> sphere(center).map { ParticleMorphSlot(it, -28.0, 36.0, 0.68) }
        }

        private fun caption(text: String, center: Vec3, font: Font): List<ParticleMorphSlot> =
            Draw.textPoints(text, center, count, captionHeight, font).map { ParticleMorphSlot(it, 0.0, 0.0, captionSize) }

        private fun cube(center: Vec3): List<Vec3> = buildList(count) {
            val last = cubeSide - 1
            fun axis(i: Int): Double = (i.toDouble() / last - 0.5) * 4.2
            for (x in 0..last) for (y in 0..last) for (z in 0..last) {
                if (x == 0 || x == last || y == 0 || y == last || z == 0 || z == last) {
                    add(center.add(axis(x), axis(y), axis(z)))
                }
            }
        }

        private fun sphere(center: Vec3): List<Vec3> = List(count) { i ->
            val y = 1.0 - 2.0 * (i + 0.5) / count
            val r = sqrt(1.0 - y * y)
            val angle = i * PI * (3.0 - sqrt(5.0))
            center.add(2.2 * r * cos(angle), 2.2 * y, 2.2 * r * sin(angle))
        }
    }
}
