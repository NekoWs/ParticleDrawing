package work.nekow.particledrawing.animation.script

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.ln

/**
 * 快速标量数学近似（仅 process 且 fx.fastMath 开启时使用）。
 *
 * 与编辑器 src/core/fastmath.js 逐位一致：同一套公式与双精度 IEEE 754 运算顺序。
 * 目标精度：sin/cos/tan 最大绝对误差 ≤ 1e-4，其余相对误差 ≤ 1e-4。
 */
object ScriptFastMath {

    private const val HALF_PI = PI / 2
    private const val TWO_PI = PI * 2
    private const val INV_TWO_PI = 0.15915494309189535
    private const val LN2 = 0.6931471805599453
    private const val LOG2E = 1.4426950408889634
    private const val MIN_NORMAL = 2.2250738585072014e-308
    // 双精度位布局：保留符号位(63)与尾数位(0..51)，清除指数位(52..62)。
    private val SIGN_MANTISSA_MASK: Long = (1L shl 63) or ((1L shl 52) - 1L)

    /* ---- sin / cos ---- */

    private fun reduceAngle(x: Double): Double = x - floor(x * INV_TWO_PI + 0.5) * TWO_PI

    private fun sinPoly(x: Double): Double {
        val x2 = x * x
        var p = 1.6059043836821613e-10
        p = p * x2 - 2.505210838544172e-8
        p = p * x2 + 2.7557319223985893e-6
        p = p * x2 - 0.00019841269841269841
        p = p * x2 + 0.008333333333333333
        p = p * x2 - 0.16666666666666666
        p = p * x2 + 1.0
        return p * x
    }

    fun fastSin(x: Double): Double {
        if (!x.isFinite()) return kotlin.math.sin(x)
        var r = reduceAngle(x)
        var s = 1.0
        if (r < 0) { r = -r; s = -1.0 }
        if (r > HALF_PI) r = PI - r
        return s * sinPoly(r)
    }

    fun fastCos(x: Double): Double = fastSin(x + HALF_PI)

    fun fastTan(x: Double): Double = fastSin(x) / fastCos(x)

    /* ---- exp ---- */

    private fun expTaylor(y: Double): Double {
        var p = 1.0 / 5040
        p = p * y + 1.0 / 720
        p = p * y + 1.0 / 120
        p = p * y + 1.0 / 24
        p = p * y + 1.0 / 6
        p = p * y + 0.5
        p = p * y + 1.0
        p = p * y + 1.0
        return p
    }

    private fun scalePow2(x: Double, n: Double): Double {
        if (!x.isFinite() || x == 0.0 || n == 0.0) return x
        if (n < -2147483648.0 || n > 2147483647.0) return x * Math.pow(2.0, n)
        val ni = n.toInt()
        val bits = java.lang.Double.doubleToRawLongBits(x)
        var e = ((bits ushr 52) and 0x7ff).toInt()
        if (e == 0) return x * Math.pow(2.0, n)
        e += ni
        if (e <= 0 || e >= 0x7ff) return x * Math.pow(2.0, n)
        val newBits = (bits and SIGN_MANTISSA_MASK) or (e.toLong() shl 52)
        return java.lang.Double.longBitsToDouble(newBits)
    }

    fun fastExp(x: Double): Double {
        if (x.isNaN()) return x
        if (x == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
        if (x == Double.NEGATIVE_INFINITY) return 0.0
        val n = floor(x * LOG2E + 0.5)
        val f = x - n * LN2
        return scalePow2(expTaylor(f), n)
    }

    /* ---- log ---- */

    private fun logMantissa(m: Double): Double {
        val u = (m - 1) / (m + 1)
        val u2 = u * u
        var p = 1.0 / 13
        p = p * u2 + 1.0 / 11
        p = p * u2 + 1.0 / 9
        p = p * u2 + 1.0 / 7
        p = p * u2 + 1.0 / 5
        p = p * u2 + 1.0 / 3
        p = p * u2 + 1.0
        return 2 * u * p
    }

    fun fastLog(x: Double): Double {
        if (!x.isFinite() || x <= 0.0) return ln(x)
        if (x < MIN_NORMAL) return ln(x)
        val bits = java.lang.Double.doubleToRawLongBits(x)
        val e = ((bits ushr 52) and 0x7ff).toInt() - 1023
        val mHi = (bits and SIGN_MANTISSA_MASK) or (1023L shl 52)
        val m = java.lang.Double.longBitsToDouble(mHi)
        return e * LN2 + logMantissa(m)
    }

    /* ---- pow ---- */

    fun fastPow(a: Double, b: Double): Double {
        if (a > 0 && a.isFinite() && b.isFinite()) {
            if (b == 0.0) return 1.0
            return fastExp(b * fastLog(a))
        }
        return Math.pow(a, b)
    }

    /* ---- atan / atan2 / asin / acos ---- */

    private fun atanTaylor(u: Double): Double {
        val u2 = u * u
        var p = 1.0 / 9
        p = p * u2 - 1.0 / 7
        p = p * u2 + 1.0 / 5
        p = p * u2 - 1.0 / 3
        p = p * u2 + 1.0
        return u * p
    }

    private fun atanPoly(z: Double): Double {
        val u = z / (1 + sqrt(1 + z * z))
        return 2 * atanTaylor(u)
    }

    fun fastAtan(x: Double): Double {
        if (!x.isFinite()) return kotlin.math.atan(x)
        val ax = abs(x)
        if (ax > 1) {
            val r = atanPoly(1 / ax)
            return (if (x > 0) 1.0 else -1.0) * (HALF_PI - r)
        }
        val r = atanPoly(ax)
        return if (x >= 0) r else -r
    }

    fun fastAtan2(y: Double, x: Double): Double {
        if (x > 0) return fastAtan(y / x)
        if (x < 0) {
            if (y >= 0) return fastAtan(y / x) + PI
            return fastAtan(y / x) - PI
        }
        if (y > 0) return HALF_PI
        if (y < 0) return -HALF_PI
        return 0.0
    }

    fun fastAsin(x: Double): Double {
        if (x > 1 || x < -1) return Double.NaN
        if (x == 1.0) return HALF_PI
        if (x == -1.0) return -HALF_PI
        return fastAtan(x / sqrt(1 - x * x))
    }

    fun fastAcos(x: Double): Double = HALF_PI - fastAsin(x)

    /** 内建名 → 快速实现；与编辑器 fastmath.js 的 FAST_MATH 表一致。 */
    val FAST_MATH: Map<String, (List<Double>) -> Double> = mapOf(
        "sin" to { a -> fastSin(a[0]) },
        "cos" to { a -> fastCos(a[0]) },
        "tan" to { a -> fastTan(a[0]) },
        "asin" to { a -> fastAsin(a[0]) },
        "acos" to { a -> fastAcos(a[0]) },
        "atan" to { a -> fastAtan(a[0]) },
        "atan2" to { a -> fastAtan2(a[0], a[1]) },
        "exp" to { a -> fastExp(a[0]) },
        "log" to { a -> fastLog(a[0]) },
        "ln" to { a -> fastLog(a[0]) },
        "pow" to { a -> fastPow(a[0], a[1]) },
    )
}