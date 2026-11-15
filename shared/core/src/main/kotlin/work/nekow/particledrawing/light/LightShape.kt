package work.nekow.particledrawing.light

/**
 * 一个光源的形状与衰减描述，挂在粒子的外观上（可空 = 老行为：点光、线性衰减、用全局半径）。
 *
 * 所有角度都是「锥半角」的度数：`outerDeg` 是光斑边界，`innerDeg` 是满亮区边界，
 * `innerDeg == 0` 时按 `outerDeg * 0.6` 自动给一个软边。方向只给偏航/俯仰，
 * 由 [LightMath.directionOf] 转成单位向量，省得每帧归一化。
 */
data class LightShape(
    /** 照射半径（格）；<= 0 表示沿用全局配置。 */
    val range: Double = 0.0,
    /** 外锥半角（度）；<= 0 表示点光。 */
    val outerDeg: Double = 0.0,
    /** 内锥半角（度）；<= 0 时取外锥的 60%。 */
    val innerDeg: Double = 0.0,
    /** 边缘锐度：1 平滑，越大越硬。 */
    val edge: Double = 1.0,
    /** 偏航（度）：绕 Y 轴，0 = 朝 +Z。 */
    val yawDeg: Double = 0.0,
    /** 俯仰（度）：-90 = 朝下。 */
    val pitchDeg: Double = -90.0,
    val falloff: LightFalloff = LightFalloff.LINEAR,
) {
    /** 是否真的构成射灯（有锥角）。 */
    val isSpot: Boolean get() = outerDeg > 0.0

    /** 内锥的最终度数：没写就取外锥的 60%；内外锥相等时使用硬边。 */
    val effectiveInnerDeg: Double
        get() = when {
            outerDeg <= 0.0 -> 0.0
            innerDeg <= 0.0 -> outerDeg * 0.6
            else -> innerDeg
        }

    fun validated(): LightShape {
        require(range.isFinite() && range in 0.0..MAX_RANGE) { "光照半径必须在 0 到 64 格之间" }
        require(outerDeg.isFinite() && outerDeg in 0.0..179.0) { "射灯外锥半角必须在 0 到 179 度之间" }
        require(innerDeg.isFinite() && innerDeg >= 0.0) { "射灯内锥半角不能为负" }
        require(innerDeg <= outerDeg) { "射灯内锥角不能大于外锥角" }
        require(edge.isFinite() && edge >= 1.0) { "边缘锐度不能小于 1" }
        require(pitchDeg.isFinite() && pitchDeg in -90.0..90.0) { "射灯俯仰必须在 -90 到 90 度之间" }
        require(yawDeg.isFinite()) { "射灯偏航必须是有限数" }
        return this
    }

    /** 改世界坐标中的偏航和俯仰，单位为度。 */
    fun withDirection(yawDeg: Double, pitchDeg: Double): LightShape =
        copy(yawDeg = yawDeg, pitchDeg = pitchDeg).validated()

    /** 改锥半角。省略内锥时取外锥的 60%，省略锐度时沿用当前值。 */
    @JvmOverloads
    fun withFocus(outerDeg: Double, innerDeg: Double = 0.0, edge: Double = this.edge): LightShape =
        copy(outerDeg = outerDeg, innerDeg = innerDeg, edge = edge).validated()

    /** 改距离衰减。半径省略时沿用当前值。 */
    @JvmOverloads
    fun withFalloff(falloff: LightFalloff, range: Double = this.range): LightShape =
        copy(falloff = falloff, range = range).validated()

    /** 方向向量。 */
    fun direction(): Triple<Double, Double, Double> {
        if (!isSpot) return Triple(0.0, 0.0, 0.0)
        val v = LightMath.directionOf(yawDeg, pitchDeg)
        return Triple(v.x, v.y, v.z)
    }

    fun innerCos(): Double = LightMath.cosOfDegrees(effectiveInnerDeg)

    fun outerCos(): Double = LightMath.cosOfDegrees(outerDeg.coerceAtLeast(0.0))

    override fun toString(): String =
        "LightShape(range=$range, outer=$outerDeg°, inner=$effectiveInnerDeg°, edge=$edge, " +
            "yaw=$yawDeg°, pitch=$pitchDeg°, falloff=$falloff)"

    companion object {
        /** 老行为：点光、线性衰减、半径用全局值。 */
        val POINT = LightShape()
        const val MAX_RANGE = 64.0

        /** 彩色点光。半径为 0 时使用客户端默认值。 */
        @JvmStatic
        @JvmOverloads
        fun point(range: Double = 0.0, falloff: LightFalloff = LightFalloff.LINEAR): LightShape =
            LightShape(range = range, falloff = falloff).validated()

        /** 射灯，默认垂直朝下。 */
        @JvmStatic
        @JvmOverloads
        fun spot(range: Double, outerDeg: Double, innerDeg: Double = 0.0, edge: Double = 1.0): LightShape =
            LightShape(range = range, outerDeg = outerDeg, innerDeg = innerDeg, edge = edge).validated()
    }
}
