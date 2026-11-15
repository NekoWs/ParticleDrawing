package examples

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.morph.MorphEasing
import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphWobble
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.api.ParticleMorphSlot
import work.nekow.particledrawing.api.ParticleSpawnSpec
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 字幕位于世界 XY 平面，从 +Z 一侧观看。各方法在服务端主线程调用。 */
class SubtitleMorph private constructor(val group: ParticleGroup, private val center: Vec3) {
    enum class Target { FIRST_CAPTION, SECOND_CAPTION, CUBE, SPHERE }

    fun show(target: Target): SubtitleMorph = apply {
        group.morphSlots(slots(target, center), transition)
    }

    fun remove() = group.remove()

    companion object {
        private const val glyphSize = 16
        private const val letterStep = 19
        private const val pixelStep = 0.151
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

        fun spawn(manager: ParticleManager, center: Vec3): SubtitleMorph {
            val appearance = ParticleVisual().style(ParticleStyle.SOFT_DOT).billboard(false)
            val particles = manager.spawnAll(slots(Target.FIRST_CAPTION, center).map { slot ->
                ParticleSpawnSpec().position(slot.position).visual(appearance)
                    .color(Color.ofInt(136, 229, 255, 255)).scale(captionSize.toFloat()).glowing(true).lightLevel(0)
            })
            if (particles.any { it == null }) {
                particles.forEach { it?.remove() }
                error("粒子容量不足，字幕需要 $count 颗粒子")
            }
            val group = manager.createGroup(center)
            particles.forEach(group::add)
            return SubtitleMorph(group, center)
        }

        private fun slots(target: Target, center: Vec3): List<ParticleMorphSlot> = when (target) {
            Target.FIRST_CAPTION -> caption("粒子字幕", center).map { ParticleMorphSlot(it, 0.0, 0.0, captionSize) }
            Target.SECOND_CAPTION -> caption("变换形状", center).map { ParticleMorphSlot(it, 0.0, 0.0, captionSize) }
            Target.CUBE -> cube(center).map { point ->
                val yaw = 54.0 + 8.0 * (point.x - center.x)
                val size = 1.8 + 0.25 * (point.y - center.y)
                ParticleMorphSlot(point, yaw, -32.0, size)
            }
            Target.SPHERE -> sphere(center).map { ParticleMorphSlot(it, -28.0, 36.0, 0.68) }
        }

        private fun caption(text: String, center: Vec3): List<Vec3> {
            val width = (text.length - 1) * letterStep + glyphSize
            val pixels = buildList {
                for ((index, char) in text.withIndex()) {
                    for ((y, row) in glyphs.getValue(char).withIndex()) {
                        for (x in 0 until glyphSize) {
                            if ((row and (1 shl (glyphSize - 1 - x))) != 0) {
                                add(center.add(
                                    (index * letterStep + x - (width - 1) * 0.5) * pixelStep,
                                    ((glyphSize - 1) * 0.5 - y) * pixelStep,
                                    0.0,
                                ))
                            }
                        }
                    }
                }
            }
            require(pixels.size in 1..count)
            // 均匀铺开附加采样，让不同笔画数的字幕与立方体共享同一批粒子。
            return List(count) { index ->
                val pixelIndex = index * pixels.size / count
                val first = (pixelIndex * count + pixels.size - 1) / pixels.size
                val end = ((pixelIndex + 1) * count + pixels.size - 1) / pixels.size
                val dx = (index - (first + end - 1) * 0.5) * pixelStep * 0.30
                pixels[pixelIndex].add(dx, 0.0, 0.0)
            }
        }

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

        private val glyphs = mapOf(
            '粒' to intArrayOf(
                0x1830, 0x1830, 0xda30, 0x7ffe, 0x7dfe, 0x3800, 0xff86, 0xffce,
                0x38cc, 0x7ccc, 0x7ecc, 0xf8ec, 0xd818, 0x1bff, 0x1bff, 0x1800,
            ),
            '子' to intArrayOf(
                0x7ffe, 0x7ffe, 0x003c, 0x0078, 0x01f0, 0x01c0, 0x01c0, 0xffff,
                0xffff, 0x01c0, 0x01c0, 0x01c0, 0x01c0, 0x1f80, 0x1f80, 0x1f00,
            ),
            '字' to intArrayOf(
                0x0180, 0x03c0, 0xffff, 0xffff, 0xe007, 0xeff7, 0x1ff8, 0x00f0,
                0x00e0, 0xffff, 0xffff, 0x01c0, 0x01c0, 0x01c0, 0x0f80, 0x0f80,
            ),
            '幕' to intArrayOf(
                0x0e60, 0xffff, 0x0e70, 0x3ffc, 0x3ffc, 0x3ffc, 0x301c, 0x3ffc,
                0x3ff8, 0xfffe, 0x39b8, 0x7ffc, 0xffff, 0x39ba, 0x19b8, 0x0180,
            ),
            '变' to intArrayOf(
                0x0180, 0x0380, 0xffff, 0x7ffe, 0x1668, 0x3e7c, 0xf66f, 0x6666,
                0x3ff8, 0x3ff8, 0x0c38, 0x07f0, 0x07e0, 0x1ffe, 0x7e7e, 0x700e,
            ),
            '换' to intArrayOf(
                0x30c0, 0x31fc, 0x31fc, 0xfb1c, 0xff18, 0x33fe, 0x33fe, 0x3b66,
                0xfb66, 0xf366, 0x3fff, 0x36f2, 0x30f8, 0x33de, 0xf78e, 0x6602,
            ),
            '形' to intArrayOf(
                0x0004, 0x7f8e, 0x7f9c, 0x3378, 0x3330, 0x3306, 0x7f8e, 0xffdc,
                0x7ff8, 0x3330, 0x3326, 0x330e, 0x731c, 0x6378, 0xe370, 0x4360,
            ),
            '状' to intArrayOf(
                0x0c64, 0x0c6e, 0x4c66, 0x6c60, 0x6d78, 0x7fff, 0x3dfe, 0x0c70,
                0x1c70, 0x7cf8, 0xfcd8, 0xecdc, 0x4dcc, 0x0f8e, 0x0f06, 0x0d02,
            ),
        )
    }
}
