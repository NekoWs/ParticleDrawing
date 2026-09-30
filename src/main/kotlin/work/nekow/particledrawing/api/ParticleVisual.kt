package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.util.VisualMath
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 逐粒子外观规格：贴图 / 子矩形 UV / 各向异性尺寸 / 朝向 / 加法混合 / 免光照。
 *
 * **生成时定死**：PD 只在生成那一次把外观同步给客户端（不产生任何逐 tick 开销），
 * 之后改不了——要换外观就销毁重生成。
 *
 * **尺寸口径**（两种二选一，不看贴图的调用方只需要知道第一条）：
 * - 编辑器单位（默认，与 `scale` 同一口径）：渲染整宽 = 值 × 0.2 × 贴图尺寸系数（系数 = 贴图最长边 / 16，
 *   无贴图时 1）。想反过来推「这条丝有多粗」就乘 0.2 再乘系数。
 * - 世界格（[anisoWorld] / [ParticleVisual.anisoWorld]）：直接给整宽/整高，单位是格，
 *   与贴图尺寸无关——`Draw.polyline` 这类几何图元用它。
 *
 * 可变对象 + 链式方法：[copy] 一份改局部，避免多颗粒子共用同一个实例互相踩。
 */
class ParticleVisual {

    /** 贴图名：`ParticleManager.registerTexture` 注册的名字，或 [ParticleStyle] 的内置名；null = 纯白方块。 */
    var texture: String? = null

    /** 子矩形 UV（贴图像素，顺序 u0, v0, u1, v1）；null = 整图。UV 起点同时是尺寸系数的基准。 */
    var uvRect: FloatArray? = null

    /** 各向异性长轴（四边形局部 X）尺寸；<=0 表示该轴沿用粒子的 `scale`。 */
    var scaleW: Float = 0f

    /** 各向异性短轴（四边形局部 Y）尺寸；<=0 表示该轴沿用粒子的 `scale`。 */
    var scaleH: Float = 0f

    /** 尺寸口径：false = 编辑器单位（同 `scale`）；true = 世界格整宽/整高。 */
    var worldUnits: Boolean = false

    /** 广告牌：true = 永远面向相机；false = 朝向固定（世界 +Z 起算），此时下面的自转角生效。 */
    var billboard: Boolean = true

    /** 自转角（度；X→Y→Z 内旋，与渲染层同一约定）。公开入口给的是弧度，见 [spin]。 */
    var spinXDeg: Double = 0.0
    var spinYDeg: Double = 0.0
    var spinZDeg: Double = 0.0

    /** 自转轴系：true = 局部轴（内旋）；false = 世界轴（外旋）。 */
    var spinLocal: Boolean = true

    /** 加法混合：亮部叠亮、有溢出感。与 [glowing] 不同——glowing 只免光照，加法才让颜色相加。 */
    var additive: Boolean = false

    /** 免光照（全亮，不受世界光照影响）。 */
    var glowing: Boolean = false

    /** 发光粒子向外发出的光照等级 (0-15)，仅当 [glowing] 为 true 时生效。 */
    var lightLevel: Int = 15

    // —— 链式设置 ——

    /** 用整张贴图（[name] 见 [texture]）；会清掉之前设的子矩形 UV。 */
    fun texture(name: String): ParticleVisual {
        texture = name
        uvRect = null
        return this
    }

    /** 用内置形状（[ParticleStyle.SQUARE] 等价于「无贴图」）。 */
    fun style(style: ParticleStyle): ParticleVisual {
        texture = style.textureName
        uvRect = null
        return this
    }

    /** 取贴图的子矩形（贴图像素，顺序 u0, v0, u1, v1）。 */
    fun uv(u0: Float, v0: Float, u1: Float, v1: Float): ParticleVisual {
        uvRect = floatArrayOf(u0, v0, u1, v1)
        return this
    }

    /** 各向异性尺寸（编辑器单位）：长轴 [w]、短轴 [h]。 */
    fun aniso(w: Float, h: Float): ParticleVisual {
        scaleW = w
        scaleH = h
        worldUnits = false
        return this
    }

    /** 各向异性尺寸（世界格整宽/整高）：与贴图尺寸无关。 */
    fun anisoWorld(w: Float, h: Float): ParticleVisual {
        scaleW = w
        scaleH = h
        worldUnits = true
        return this
    }

    /** 广告牌开关；false 时朝向固定，需要自己给 [spin] 或 [alignTo]。 */
    fun billboard(enabled: Boolean): ParticleVisual {
        billboard = enabled
        return this
    }

    /** 绕四边形法线（局部 Z）旋转 [radians] 弧度。 */
    @JvmOverloads
    fun spin(radians: Double, spinLocal: Boolean = true): ParticleVisual =
        spin(0.0, 0.0, radians, spinLocal)

    /** 三轴自转（弧度）。[spinLocal] = false 时按世界轴外旋。 */
    @JvmOverloads
    fun spin(radiansX: Double, radiansY: Double, radiansZ: Double, spinLocal: Boolean = true): ParticleVisual {
        spinXDeg = Math.toDegrees(radiansX)
        spinYDeg = Math.toDegrees(radiansY)
        spinZDeg = Math.toDegrees(radiansZ)
        this.spinLocal = spinLocal
        return this
    }

    /**
     * 把长轴（四边形局部 X）对齐到 `to - from` 方向，用于「一条丝沿线段躺好」。
     *
     * 会一并关掉广告牌（广告牌下自转不生效）。方向为零向量时保持默认朝向。
     */
    fun alignTo(from: Vec3, to: Vec3): ParticleVisual {
        val e = VisualMath.longAxisEulerDegrees(to.x - from.x, to.y - from.y, to.z - from.z)
        spinXDeg = e[0]
        spinYDeg = e[1]
        spinZDeg = e[2]
        spinLocal = true
        billboard = false
        return this
    }

    /** 加法混合开关。 */
    fun additive(enabled: Boolean): ParticleVisual {
        additive = enabled
        return this
    }

    /** 免光照开关。 */
    fun glowing(enabled: Boolean): ParticleVisual {
        glowing = enabled
        return this
    }

    /** 发光等级 (0-15)。 */
    fun lightLevel(level: Int): ParticleVisual {
        lightLevel = level.coerceIn(0, 15)
        return this
    }

    /** 复制一份（含 [uvRect] 数组本身），供「同一份外观 + 局部微调」的场景用。 */
    fun copy(): ParticleVisual {
        val c = ParticleVisual()
        c.texture = texture
        c.uvRect = uvRect?.copyOf()
        c.scaleW = scaleW
        c.scaleH = scaleH
        c.worldUnits = worldUnits
        c.billboard = billboard
        c.spinXDeg = spinXDeg
        c.spinYDeg = spinYDeg
        c.spinZDeg = spinZDeg
        c.spinLocal = spinLocal
        c.additive = additive
        c.glowing = glowing
        c.lightLevel = lightLevel
        return c
    }

    /** 是否给了各向异性尺寸（任一轴 > 0）。 */
    fun hasAniso(): Boolean = scaleW > 0f || scaleH > 0f

    /** 是否一个字都没设过（默认外观，渲染成纯白方块）：[Draw] 靠它跳过无谓的对象拷贝。 */
    fun isPristine(): Boolean = texture == null && uvRect == null && !hasAniso() &&
        billboard && spinXDeg == 0.0 && spinYDeg == 0.0 && spinZDeg == 0.0 && spinLocal &&
        !additive && !glowing

    /**
     * 解析成渲染用的 UV（客户端调用）：[texW]/[texH] 是已注册贴图的像素尺寸（未知时传 0）。
     * 无贴图返回 null。
     */
    fun toUvData(texW: Int, texH: Int): UvData? {
        val name = texture ?: return null
        val rect = uvRect
        val x0: Int
        val y0: Int
        val w: Int
        val h: Int
        if (rect != null) {
            x0 = rect[0].roundToInt()
            y0 = rect[1].roundToInt()
            w = max(1, (rect[2] - rect[0]).roundToInt())
            h = max(1, (rect[3] - rect[1]).roundToInt())
        } else {
            x0 = 0
            y0 = 0
            w = if (texW > 0) texW else 16
            h = if (texH > 0) texH else 16
        }
        // STATIC：始终取 (x0, y0) 起的 w×h 像素；texSize 决定渲染尺寸系数
        return UvData(
            name, UvData.Mode.STATIC,
            intArrayOf(w, h), intArrayOf(x0, y0),
            intArrayOf(w, h), intArrayOf(w, h),
            1f, 0, true,
        )
    }

    /**
     * 解析成渲染用的各向异性缩放三元组（客户端调用）：[fallbackScale] = 粒子自身的 `scale`，
     * [texScale] = 贴图尺寸系数（[toUvData] 的 `texSize` 最长边 / 16）。
     * 返回 [长轴, 短轴, 1]；未给各向异性时返回 null。
     */
    fun resolvedAniso(fallbackScale: Float, texScale: Float): FloatArray? {
        if (!hasAniso()) return null
        val w = if (scaleW > 0f) scaleW else fallbackScale
        val h = if (scaleH > 0f) scaleH else fallbackScale
        if (!worldUnits) return floatArrayOf(w, h, 1f)
        return floatArrayOf(
            VisualMath.editorUnitsForWorldWidth(w, texScale),
            VisualMath.editorUnitsForWorldWidth(h, texScale),
            1f,
        )
    }

    companion object {
        /**
         * 贴图尺寸系数：UV 取景框（[UvData.texSize]）最长边 / 16，基准 16px 时为 1。
         * 渲染整宽 = 编辑器尺寸 × 0.2 × 本系数；[worldUnits] 口径下换算时也用它。
         */
        @JvmStatic
        fun texScale(uv: UvData?): Float {
            val size = uv?.texSize ?: return 1f
            val maxDim = max(size[0], size[1])
            return if (maxDim > 0) maxDim / 16f else 1f
        }
    }
}
