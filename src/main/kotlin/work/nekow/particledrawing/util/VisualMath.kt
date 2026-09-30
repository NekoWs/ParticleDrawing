package work.nekow.particledrawing.util

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 尺寸口径与朝向换算：两端（服务端算载荷、客户端算尺寸）共用一份纯数学，便于单测。
 */
object VisualMath {

    /**
     * 编辑器单位 → Minecraft 世界单位的系数。
     *
     * 原版 quad 顶点把 scale 当「半宽」用（±scale），而编辑器里的尺寸是整宽，
     * 所以取编辑器尺寸因子的**一半**：编辑器尺寸 1 = 世界整宽 0.2 格。
     */
    const val EDITOR_TO_MC_SCALE: Float = 0.1f

    /** 编辑器尺寸 → 世界整宽（格）：仅用于文档与断言，渲染路径不读它。 */
    const val EDITOR_UNIT_WIDTH: Float = 0.2f

    /**
     * 世界格整宽 → 编辑器单位尺寸：给定贴图尺寸系数 [texScale]（贴图最长边 / 16，无贴图 1）时，
     * 让渲染出来的整宽正好等于 [worldBlocks]。
     */
    @JvmStatic
    fun editorUnitsForWorldWidth(worldBlocks: Float, texScale: Float): Float {
        val scale = if (texScale > 0f) texScale else 1f
        return worldBlocks / (2f * EDITOR_TO_MC_SCALE * scale)
    }

    /**
     * 求一组欧拉角（度；X→Y→Z **内旋**，与 `BridgeParticle.orientationQuaternion(spinLocal=true)` 同约定），
     * 使四边形局部 X 轴（长轴）指向 [dx]/[dy]/[dz]，局部 Z（法线）尽量不含世界 Y 分量。
     *
     * 用于「一条丝沿线段方向躺好」：给线段两端点之差即可。
     * 方向为零向量时返回全 0（保持默认朝向）。
     */
    @JvmStatic
    fun longAxisEulerDegrees(dx: Double, dy: Double, dz: Double): DoubleArray {
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-9) return DoubleArray(3)

        // 局部 X = 长轴方向
        val xx = dx / len
        val xy = dy / len
        val xz = dz / len

        // 参考上方向：长轴与它近乎平行时换一根，避免叉积退化
        var uy = 1.0
        var uz = 0.0
        if (abs(xy) > 0.999) {
            uy = 0.0
            uz = 1.0
        }

        // 局部 Z（法线）= X × up，局部 Y = Z × X，得到右手正交基
        var zx = xy * uz - xz * uy
        var zy = xz * 0.0 - xx * uz
        var zz = xx * uy - xy * 0.0
        val zl = sqrt(zx * zx + zy * zy + zz * zz)
        if (zl < 1e-9) return DoubleArray(3)
        zx /= zl
        zy /= zl
        zz /= zl

        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz

        // 旋转矩阵三列 = (X, Y, Z)；按 R = Rx(a)·Ry(b)·Rz(c) 反解
        val sinB = zx.coerceIn(-1.0, 1.0)
        val b = asin(sinB)
        var a: Double
        var c: Double
        if (abs(sinB) > 0.9999) {
            // 万向锁：a 与 c 不可分，把相位全给 a（c 取 0）
            c = 0.0
            a = if (sinB > 0) atan2(xy, yy) else atan2(-xy, yy)
        } else {
            a = atan2(-zy, zz)
            c = atan2(-yx, xx)
        }
        return doubleArrayOf(Math.toDegrees(a), Math.toDegrees(b), Math.toDegrees(c))
    }
}
