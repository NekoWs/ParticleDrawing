package work.nekow.particledrawing.animation.script

import kotlin.math.floor

/**
 * mulberry32 PRNG 与 3D Simplex 噪声。
 *
 * 算法逐行复刻编辑器 script-lang.js：PRNG 用 Int 模拟 32 位回绕；
 * Simplex 使用标准 Gustavson Grad3 表 + 由种子经 mulberry32 Fisher-Yates
 * 打乱的 [0..255] 排列表，最后 clamp 到 [-1,1]。
 */

/** 标准 mulberry32：返回无参函数，每次推进并返回 [0,1)。state 为 32 位有符号 Int。 */
fun mulberry32(seed: Int): () -> Double {
    var a = seed
    return {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        val r = t xor (t ushr 14)
        ((r.toLong() and 0xffffffffL).toDouble() / 4294967296.0)
    }
}

private val GRAD3 = arrayOf(
    intArrayOf(1, 1, 0), intArrayOf(-1, 1, 0), intArrayOf(1, -1, 0), intArrayOf(-1, -1, 0),
    intArrayOf(1, 0, 1), intArrayOf(-1, 0, 1), intArrayOf(1, 0, -1), intArrayOf(-1, 0, -1),
    intArrayOf(0, 1, 1), intArrayOf(0, -1, 1), intArrayOf(0, 1, -1), intArrayOf(0, -1, -1),
)

private const val SIMPLEX_F3 = 1.0 / 3.0
private const val SIMPLEX_G3 = 1.0 / 6.0

private class Permutation(val perm: IntArray, val permMod12: IntArray)

private val simplexCache = HashMap<Int, Permutation>()

private fun makePermutation(seed: Int): Permutation {
    simplexCache[seed]?.let { return it }
    val p = IntArray(256) { it }
    val rng = mulberry32(seed)
    // 固定 Fisher-Yates 顺序：i 从 255 递减到 1，j = floor(rng() * (i + 1))。
    for (i in 255 downTo 1) {
        val j = floor(rng() * (i + 1)).toInt()
        val tmp = p[i]
        p[i] = p[j]
        p[j] = tmp
    }
    val perm = IntArray(512) { p[it and 255] }
    val permMod12 = IntArray(512) { perm[it] % 12 }
    return Permutation(perm, permMod12).also { simplexCache[seed] = it }
}

private fun grad3Dot(gi: Int, x: Double, y: Double, z: Double): Double {
    val g = GRAD3[gi]
    return g[0] * x + g[1] * y + g[2] * z
}

/** 3D Simplex 噪声，结果 clamp 到 [-1,1]（与 JS noise3D 一致）。 */
fun noise3D(xin: Double, yin: Double, zin: Double, seed: Int): Double {
    val p = makePermutation(seed)
    val perm = p.perm
    val permMod12 = p.permMod12

    val s = (xin + yin + zin) * SIMPLEX_F3
    val i = floor(xin + s)
    val j = floor(yin + s)
    val k = floor(zin + s)
    val t = (i + j + k) * SIMPLEX_G3
    val x0 = i - t
    val y0 = j - t
    val z0 = k - t
    val x0r = xin - x0
    val y0r = yin - y0
    val z0r = zin - z0

    var i1: Int
    var j1: Int
    var k1: Int
    var i2: Int
    var j2: Int
    var k2: Int
    if (x0r >= y0r) {
        if (y0r >= z0r) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0 }
        else if (x0r >= z0r) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1 }
        else { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1 }
    } else {
        if (y0r < z0r) { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1 }
        else if (x0r < z0r) { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1 }
        else { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0 }
    }

    val x1 = x0r - i1 + SIMPLEX_G3
    val y1 = y0r - j1 + SIMPLEX_G3
    val z1 = z0r - k1 + SIMPLEX_G3
    val x2 = x0r - i2 + 2 * SIMPLEX_G3
    val y2 = y0r - j2 + 2 * SIMPLEX_G3
    val z2 = z0r - k2 + 2 * SIMPLEX_G3
    val x3 = x0r - 1 + 3 * SIMPLEX_G3
    val y3 = y0r - 1 + 3 * SIMPLEX_G3
    val z3 = z0r - 1 + 3 * SIMPLEX_G3

    // JS 中 i/j/k 经按位与 255（ToInt32 后取低 8 位）。
    val ii = doubleToLow8(i)
    val jj = doubleToLow8(j)
    val kk = doubleToLow8(k)

    val gi0 = permMod12[ii + perm[jj + perm[kk]]]
    val gi1 = permMod12[ii + i1 + perm[jj + j1 + perm[kk + k1]]]
    val gi2 = permMod12[ii + 1 + perm[jj + 1 + perm[kk + 1]]]
    val gi3 = permMod12[ii + i2 + perm[jj + j2 + perm[kk + k2]]]

    var n0 = 0.0
    var n1 = 0.0
    var n2 = 0.0
    var n3 = 0.0

    var t0 = 0.6 - x0r * x0r - y0r * y0r - z0r * z0r
    if (t0 >= 0) { t0 *= t0; n0 = t0 * t0 * grad3Dot(gi0, x0r, y0r, z0r) }
    var t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1
    if (t1 >= 0) { t1 *= t1; n1 = t1 * t1 * grad3Dot(gi1, x1, y1, z1) }
    var t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2
    if (t2 >= 0) { t2 *= t2; n2 = t2 * t2 * grad3Dot(gi2, x2, y2, z2) }
    var t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3
    if (t3 >= 0) { t3 *= t3; n3 = t3 * t3 * grad3Dot(gi3, x3, y3, z3) }

    val out = 32.0 * (n0 + n1 + n2 + n3)
    return out.coerceIn(-1.0, 1.0)
}

/** JS `double & 255`：ToInt32 截断/回绕后取低 8 位。 */
private fun doubleToLow8(x: Double): Int = toInt32(x) and 255

/** 分形布朗运动：lacunarity=2.0、gain=0.5，归一化到 [-1,1]（与 JS fbm 一致）。 */
fun fbm(x: Double, y: Double, z: Double, octaves: Int, seed: Int): Double {
    if (octaves < 1) throw ScriptException("fbm octaves must be at least 1")
    var sum = 0.0
    var amp = 1.0
    var ampSum = 0.0
    var fx = x
    var fy = y
    var fz = z
    for (i in 0 until octaves) {
        sum += amp * noise3D(fx, fy, fz, seed)
        ampSum += amp
        amp *= 0.5
        fx *= 2.0
        fy *= 2.0
        fz *= 2.0
    }
    return (sum / ampSum).coerceIn(-1.0, 1.0)
}
