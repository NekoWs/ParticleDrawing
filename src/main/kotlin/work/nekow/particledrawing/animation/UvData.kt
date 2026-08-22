package work.nekow.particledrawing.animation

/**
 * 编辑器的贴图 / UV 参数（对象级静态属性，不做关键帧），对应 .pdraw 中粒子 / 函数对象的
 * `uv` 字段与顶层 `guv`（组 UV）。继承覆盖顺序：粒子 p.uv > 组 guv[gname] > 函数对象 fx.uv。
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
) {
    enum class Mode { STATIC, FILL, ANIMATED }

    /** 有效帧数上限（动画模式）。maxFrame 语义与编辑器一致：<=1 视为「自动」（不限制）。 */
    fun effectiveMaxFrame(autoFrames: Int): Int {
        val mf = if (maxFrame > 1) maxFrame else autoFrames
        return maxOf(1, minOf(mf, autoFrames))
    }

    /** 自动帧数：沿 x/y 方向各能放几格（行末换行 flipbook 布局）。 */
    fun autoFrames(texW: Int, texH: Int): Int {
        val w = if (texW > 0) texW else 1
        val h = if (texH > 0) texH else 1
        val sx = uvStart[0]
        val sy = uvStart[1]
        val stepx = uvStep[0]
        val stepy = uvStep[1]
        val nx = if (stepx > 0 && sx < w) (w - 1 - sx) / stepx + 1 else 1
        val ny = if (stepy > 0 && sy < h) (h - 1 - sy) / stepy + 1 else 1
        return maxOf(1, nx * ny)
    }
}
