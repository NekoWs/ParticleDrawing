package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.data.AtlasIds
import net.minecraft.util.Mth
import org.joml.Quaternionf
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import java.util.UUID

/**
 * 连接渲染粒子与 Minecraft 粒子系统的桥接粒子。
 * 将自定义粒子的位置、颜色和缩放属性同步到原版渲染管线中。
 *
 * 支持两类渲染：
 * - **无贴图**：沿用原版 sprite（[ParticleStyle] 对应图集精灵），纯色方块染色；
 * - **有贴图**（[uv] 非 null 且贴图已注册）：`getLayer` 返回指向 [TextureCache] 中
 *   DynamicTexture 的自定义 Layer，并按 UV 像素坐标（静态/填充/flipbook）采样。
 *
 * @param particleId 粒子唯一标识符
 * @param style 粒子样式（无贴图时的 sprite；有贴图时仅作回退）
 * @param level 客户端世界实例
 * @param x 初始 X 坐标
 * @param y 初始 Y 坐标
 * @param z 初始 Z 坐标
 * @param color 初始颜色
 * @param scale 初始缩放（编辑器数据模型值，已含缩放因子）
 * @param isGlowing 是否发光
 * @param uv 编辑器的 UV 参数（已解析的最终作用域值）；null 或无贴图时退化为 sprite 渲染
 */
@Suppress("unused")
class BridgeParticle(
    val particleId: UUID,
    style: ParticleStyle,
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    color: Color,
    scale: Float,
    private var isGlowing: Boolean,
    private var uv: UvData? = null
) : SingleQuadParticle(level, x, y, z, getSpriteForStyle(style)) {

    // 贴图解析结果（贴图已注册时才非 null）：Identifier + 尺寸
    @Volatile
    private var texEntry: TextureCache.Entry? = resolveTexture()

    // flipbook 计时起点（墙钟，与编辑器 performance.now()/1000 语义一致）
    private val animStartNanos: Long = System.nanoTime()

    // 贴图大小缩放因子：使用用户设置的 texSize / 16（基准 16px），用于控制贴图粒子的显示尺寸
    private val texScale: Float = computeTexScale()

    // 非均匀缩放：width 沿相机 X 轴（水平），height 沿相机 Y 轴（垂直），单位 Minecraft 块
    private var scaleW: Float = 0f
    private var scaleH: Float = 0f

    /** 计算贴图大小缩放因子（使用用户设置的 texSize，基准 16px，越大粒子越大）。 */
    private fun computeTexScale(): Float {
        val u = uv ?: return 1f
        val maxDim = maxOf(u.texSize[0], u.texSize[1])
        return if (maxDim > 0) maxDim / 16f else 1f
    }

    init {
        xo = x
        yo = y
        zo = z

        setColor(color.r, color.g, color.b)
        alpha = color.a
        // 纳入贴图大小缩放：texSize 越大粒子越大
        scaleW = scale * EDITOR_TO_MC_SCALE * texScale
        scaleH = scaleW  // 标量初始化为正方形
        quadSize = scaleW  // 兼容原版字段（getQuadSize 回退）
        lifetime = Int.MAX_VALUE
        gravity = 0f
        hasPhysics = false
    }

    fun isGlowing(): Boolean = isGlowing

    /** 更新 UV 参数（动画粒子 UV 为静态属性，通常只在 spawn 时设置一次）。 */
    fun setUv(uv: UvData?) {
        this.uv = uv
        this.texEntry = resolveTexture()
    }

    /** 解析当前 UV 指向的贴图（贴图在 spawn 前已由动画管理器预加载）。 */
    private fun resolveTexture(): TextureCache.Entry? {
        val tex = uv?.texture ?: return null
        return TextureCache.get(tex)
    }

    /** 更新发光状态（本地动画逐 tick 求值时同步）。 */
    fun setGlowing(glowing: Boolean) {
        isGlowing = glowing
    }

    /**
     * 同步粒子位置。
     * @param x 目标 X 坐标
     * @param y 目标 Y 坐标
     * @param z 目标 Z 坐标
     * @param snap 若为 true 则跳变到目标位置，否则平滑过渡
     */
    fun syncPosition(x: Double, y: Double, z: Double, snap: Boolean = false) {
        if (snap) {
            this.xo = x
            this.yo = y
            this.zo = z
        } else {
            this.xo = this.x
            this.yo = this.y
            this.zo = this.z
        }
        this.x = x
        this.y = y
        this.z = z
    }

    /**
     * 同步粒子颜色与透明度。
     * @param r 红色分量
     * @param g 绿色分量
     * @param b 蓝色分量
     * @param a 透明度分量
     */
    fun syncColor(r: Float, g: Float, b: Float, a: Float) {
        rCol = r
        gCol = g
        bCol = b
        alpha = a
    }

    /**
     * 同步粒子缩放（标量，均匀）。
     * @param scale 目标缩放值（编辑器数据模型值）
     */
    fun syncScale(scale: Float) {
        val s = scale * EDITOR_TO_MC_SCALE * texScale
        scaleW = s
        scaleH = s
        quadSize = s
    }

    /**
     * 同步粒子非均匀缩放（三分量数组 [sx, sy, sz]）。
     * sx → quad 宽度（相机 X 轴），sy → quad 高度（相机 Y 轴），sz 暂存数据不参与 billboard。
     * @param scaleArray 三分量缩放数组
     */
    fun syncScaleArray(scaleArray: FloatArray) {
        scaleW = scaleArray[0] * EDITOR_TO_MC_SCALE * texScale
        scaleH = scaleArray[1] * EDITOR_TO_MC_SCALE * texScale
        quadSize = scaleW  // 兼容原版字段
    }

    /**
     * 返回 quad 高度（供 QuadParticleGroup 批量渲染使用）。
     * 宽度通过 QuadParticleRenderStateMixin 在 renderVertex 中独立应用。
     */
    override fun getQuadSize(partialTick: Float): Float = scaleH

    /**
     * 重写 extractRotatedQuad：非均匀缩放时，将 scaleW 写入静态字段供 mixin 读取，
     * 然后调用父类（传入 scaleH 作为 size）。mixin 在 renderVertex 中用 scaleW 替换
     * nx 的缩放系数，实现宽度和高度独立缩放。
     */
    override fun extractRotatedQuad(
        state: QuadParticleRenderState,
        camera: net.minecraft.client.Camera,
        rotation: Quaternionf,
        partialTick: Float
    ) {
        if (scaleW != scaleH) {
            nonUniformScaleW = scaleW
            super.extractRotatedQuad(state, camera, rotation, partialTick)
            nonUniformScaleW = -1f
        } else {
            super.extractRotatedQuad(state, camera, rotation, partialTick)
        }
    }

    override fun tick() {
        age++
        if (age >= lifetime) {
            remove()
        }
    }

    override fun getLayer(): Layer {
        val entry = texEntry
        return if (entry != null) {
            val translucent = alpha < 1.0f
            Layer(translucent, entry.id, if (translucent) RenderPipelines.TRANSLUCENT_PARTICLE else RenderPipelines.OPAQUE_PARTICLE)
        } else {
            if (alpha < 1.0f || sprite.transparency().hasTranslucent()) {
                Layer.TRANSLUCENT
            } else {
                Layer.OPAQUE
            }
        }
    }

    // 使用自定义分组（无 16384 上限），绕过原版 SINGLE_QUADS 的粒子数限制
    override fun getGroup(): ParticleRenderType = BATCHED_QUADS

    // ---- UV 采样（贴图像素坐标 → 归一化 [0,1]） ----
    // 约定（与编辑器 scene.js flipY 一致）：GPU 纹理第 0 行 = PNG 顶部（NativeImage 自然顺序），
    // v = 1 - y/height。quad 顶点 v0=底部、v1=顶部（SingleQuadParticle 顶点布局）。

    private fun currentFrameIndex(): Int {
        val u = uv ?: return 0
        if (u.mode != UvData.Mode.ANIMATED) return 0
        val tex = texEntry ?: return 0
        val total = u.effectiveMaxFrame(u.autoFrames(tex.width, tex.height))
        if (total <= 1) return 0
        val fps = u.fps.coerceAtLeast(0.001f)
        val elapsed = (System.nanoTime() - animStartNanos) / 1_000_000_000.0
        val raw = (elapsed * fps).toLong()
        return if (u.loop) (raw % total).toInt() else minOf(raw, (total - 1).toLong()).toInt()
    }

    /** 当前帧的 UV 起点像素 (x, y)，含 flipbook 偏移（行末换行步进）。 */
    private fun currentUvStart(texW: Int, texH: Int): IntArray {
        val u = uv ?: return intArrayOf(0, 0)
        if (u.mode == UvData.Mode.FILL) return intArrayOf(0, 0)
        var sx = u.uvStart[0]
        var sy = u.uvStart[1]
        if (u.mode == UvData.Mode.ANIMATED) {
            val frame = currentFrameIndex()
            if (frame > 0) {
                val stepx = u.uvStep[0]
                val stepy = u.uvStep[1]
                // 行内格数（x 方向能放几格）；行末换行
                val cols = if (stepx > 0 && sx < texW) (texW - 1 - sx) / stepx + 1 else 1
                if (cols > 0) {
                    sx += (frame % cols) * stepx
                    sy += (frame / cols) * stepy
                }
            }
        }
        return intArrayOf(sx, sy)
    }

    override fun getU0(): Float {
        val entry = texEntry ?: return super.getU0()
        val u = uv ?: return super.getU0()
        if (u.mode == UvData.Mode.FILL) return 0f
        val sx = currentUvStart(entry.width, entry.height)[0]
        return (sx.toFloat() / entry.width).coerceIn(0f, 1f)
    }

    override fun getU1(): Float {
        val entry = texEntry ?: return super.getU1()
        val u = uv ?: return super.getU1()
        if (u.mode == UvData.Mode.FILL) return 1f
        val sx = currentUvStart(entry.width, entry.height)[0]
        val w = if (u.uvSize[0] > 0) u.uvSize[0] else entry.width
        return ((sx + w).toFloat() / entry.width).coerceIn(0f, 1f)
    }

    override fun getV0(): Float {
        val entry = texEntry ?: return super.getV0()
        val u = uv ?: return super.getV0()
        if (u.mode == UvData.Mode.FILL) return 0f
        val sy = currentUvStart(entry.width, entry.height)[1]
        val h = if (u.uvSize[1] > 0) u.uvSize[1] else entry.height
        return (1f - (sy + h).toFloat() / entry.height).coerceIn(0f, 1f)
    }

    override fun getV1(): Float {
        val entry = texEntry ?: return super.getV1()
        val u = uv ?: return super.getV1()
        if (u.mode == UvData.Mode.FILL) return 1f
        val sy = currentUvStart(entry.width, entry.height)[1]
        return (1f - sy.toFloat() / entry.height).coerceIn(0f, 1f)
    }

    // 光照查询缓存：原版 getLightCoords 每渲染帧都会查世界光照（含动态光照 mixin 的方块查询），
    // 5w 粒子会放大成每秒数十万次查询。光照按方块坐标变化，粒子在同一方块内可复用缓存。
    private var cachedLight = -1
    private var cacheBX = Int.MIN_VALUE
    private var cacheBY = Int.MIN_VALUE
    private var cacheBZ = Int.MIN_VALUE

    override fun getLightCoords(partialTick: Float): Int {
        if (isGlowing) {
            return 0x00F000F0
        }
        val bx = Mth.floor(this.x)
        val by = Mth.floor(this.y)
        val bz = Mth.floor(this.z)
        if (cachedLight < 0 || bx != cacheBX || by != cacheBY || bz != cacheBZ) {
            cachedLight = super.getLightCoords(partialTick)
            cacheBX = bx
            cacheBY = by
            cacheBZ = bz
        }
        return cachedLight
    }

    companion object {
        /**
         * 编辑器 → Minecraft 世界单位的缩放因子。
         */
        const val EDITOR_TO_MC_SCALE: Float = 0.2f

        /**
         * 非均匀缩放宽度（由 extractRotatedQuad 在调用父类前设置，-1 表示均匀缩放）。
         * QuadParticleRenderStateMixin 读取此字段在 renderVertex 中应用独立宽度。
         * MC 渲染线程单线程，volatile 仅保证可见性。
         */
        @JvmStatic
        @Volatile
        var nonUniformScaleW: Float = -1f

        /**
         * 根据粒子样式获取对应的纹理精灵。
         * @param style 粒子样式
         * @return 对应的纹理精灵
         */
        private fun getSpriteForStyle(style: ParticleStyle): TextureAtlasSprite {
            val atlas = Minecraft.getInstance()
                .atlasManager
                .getAtlasOrThrow(AtlasIds.PARTICLES)
            return atlas.getSprite(style.spriteLocation)
        }
    }
}
