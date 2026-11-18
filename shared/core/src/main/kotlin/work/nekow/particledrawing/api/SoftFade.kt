package work.nekow.particledrawing.api

import kotlin.math.sqrt

/**
 * 软粒子（近几何淡出）的采样与权重。
 *
 * 真正基于深度纹理的软粒子要改粒子着色器与渲染管线；这里改用 CPU 近似：
 * 在粒子周围取几个点查询方块，被挡得越多越透明。效果上能消掉粒子插进地面、
 * 贴墙时的硬切边，代价是每颗软粒子每刻几次方块查询。
 *
 * 采样点固定为 7 个：中心 + 三轴各两个方向，坐标写进调用方的数组，不分配。
 */
object SoftFade {

    /** 采样点数（中心 1 + 每轴 2）。 */
    const val SAMPLES = 7

    /** 需要写入的 double 数量：每个点 x/y/z。 */
    const val FLOATS = SAMPLES * 3

    /**
     * 填进 7 个采样点坐标（x,y,z 连续排列），半径按 [radius] 格。
     *
     * 中心点是 (0,0,0) 偏移，其余六个沿 ±x/±y/±z 偏移 [radius]。
     */
    fun sampleOffsets(radius: Double, out: DoubleArray) {
        require(out.size >= FLOATS) { "out 至少需要 $FLOATS 个位置" }
        var i = 0
        fun put(x: Double, y: Double, z: Double) {
            out[i++] = x; out[i++] = y; out[i++] = z
        }
        put(0.0, 0.0, 0.0)
        put(radius, 0.0, 0.0); put(-radius, 0.0, 0.0)
        put(0.0, radius, 0.0); put(0.0, -radius, 0.0)
        put(0.0, 0.0, radius); put(0.0, 0.0, -radius)
    }

    /**
     * 由被挡比例算出透明度系数。
     *
     * [strength] 为 1 时全被挡就是完全透明；小于 1 保留一部分可见度，避免粒子凭空消失。
     */
    fun factor(blocked: Int, samples: Int = SAMPLES, strength: Double = 0.85): Double {
        if (samples <= 0) return 1.0
        if (blocked <= 0) return 1.0
        val ratio = blocked.toDouble().coerceAtMost(samples.toDouble()) / samples
        return (1.0 - strength.coerceIn(0.0, 1.0) * ratio).coerceIn(0.0, 1.0)
    }

    /** 采样半径换算：给定的软边距离不能小于一个最小值，否则采样点全落在同一格。 */
    fun radiusOf(distance: Double): Double = if (distance <= 0.0) 0.0 else distance.coerceIn(0.05, 4.0)

    /** 单位化一个方向，供未来按视线方向采样用；当前实现未使用但保留语义一致。 */
    fun normalized(x: Double, y: Double, z: Double): DoubleArray {
        val len = sqrt(x * x + y * y + z * z)
        return if (len < 1e-9) doubleArrayOf(0.0, 0.0, 0.0) else doubleArrayOf(x / len, y / len, z / len)
    }
}