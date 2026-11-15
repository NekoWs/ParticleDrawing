package work.nekow.particledrawing.animation

/**
 * 贴图 / UV 参数（对象级静态属性，不做关键帧），对应粒子 / 函数对象的
 * `uv` 与组 UV。继承覆盖顺序：粒子 p.uv > 组 guv[gname] > 函数对象 fx.uv。
 *
 * 所有 UV 坐标单位为贴图像素；`texture == null` 表示无贴图（渲染为纯色方块）。
 */
class UvData(
    val texture: String?,
    val mode: Mode,
    val texSize: IntArray,
    val uvStart: IntArray,
    val uvSize: IntArray,
    val uvStep: IntArray,
    val fps: Float,
    val maxFrame: Int,
    val loop: Boolean,
    val uvStartExpr: Array<String?> = arrayOfNulls(2),
    val uvSizeExpr: Array<String?> = arrayOfNulls(2),
    val uvStepExpr: Array<String?> = arrayOfNulls(2),
    val fpsExpr: String? = null,
    val maxFrameExpr: String? = null,
) {
    enum class Mode { STATIC, FILL, ANIMATED }

    /** 是否含 script-lang 裸表达式字段（任一 UV 字段被配置为表达式即 true）。 */
    fun hasExpressions(): Boolean =
        uvStartExpr.any { it != null } || uvSizeExpr.any { it != null } ||
        uvStepExpr.any { it != null } || fpsExpr != null || maxFrameExpr != null

    /**
     * 有效帧数（动画模式）：maxFrame<=1（未设/旧默认 1）按自动帧数算满，
     * 否则按 maxFrame 取上限；两者都要再封顶到 [autoFrames]（不超过贴图布局格数），结果至少 1。
     */
    fun effectiveMaxFrame(autoFrames: Int): Int {
        val mf = if (maxFrame > 1) maxFrame else autoFrames
        return maxOf(1, minOf(mf, autoFrames))
    }

    private fun axisFrames(size: Int, start: Int, step: Int): Int {
        val limit = size.coerceAtLeast(1)
        return if (step > 0 && start < limit) (limit - 1 - start) / step + 1 else 1
    }

    internal fun frameColumns(texW: Int): Int = axisFrames(texW, uvStart[0], uvStep[0])

    /** 自动帧数：沿 x/y 方向各能放几格（行末换行 flipbook 布局）。 */
    fun autoFrames(texW: Int, texH: Int): Int =
        maxOf(1, frameColumns(texW) * axisFrames(texH, uvStart[1], uvStep[1]))
}
