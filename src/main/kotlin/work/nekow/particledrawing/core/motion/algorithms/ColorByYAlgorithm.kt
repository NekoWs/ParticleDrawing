package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.motion.MotionAlgorithm

/** 按 Y 坐标映射色相。 */
class ColorByYAlgorithm(override val params: DoubleArray) : MotionAlgorithm {
    override val id = "color_by_y"

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double): MotionAlgorithm.Result {
        val rel = basePos.subtract(pivot)
        val len = rel.length()
        if (len < 1e-6) return MotionAlgorithm.Result()
        val hue = ((1.0 - rel.y / len) / 2.0).toFloat().coerceIn(0f, 1f)
        return MotionAlgorithm.Result(color = Color.ofHsb(hue, 0.9f, 0.9f))
    }

    companion object Factory : MotionAlgorithm.Factory {
        override val id = "color_by_y"
        override fun create(params: DoubleArray) = ColorByYAlgorithm(params)
    }
}
