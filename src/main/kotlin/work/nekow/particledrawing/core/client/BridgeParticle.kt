package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.data.AtlasIds
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import org.joml.Quaternionf
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.Color
import java.util.UUID

// 连接渲染粒子与 Minecraft 粒子系统的桥接粒子，把自定义粒子的位置/颜色/缩放同步进原版渲染管线。
// 无贴图时染成纯色方块；有贴图（uv 非 null 且已注册）时按 UV 像素坐标（静态/填充/flipbook）采样 TextureCache 的 DynamicTexture。
@Suppress("unused")
class BridgeParticle(
    val particleId: UUID,
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    color: Color,
    scale: Float,
    private var isGlowing: Boolean,
    private var uv: UvData? = null,
    private val additive: Boolean = false
) : SingleQuadParticle(level, x, y, z, defaultSprite()) {

    // 贴图解析结果（贴图已注册时才非 null）：Identifier + 尺寸
    @Volatile
    private var texEntry: TextureCache.Entry? = resolveTexture()

    // flipbook 计时起点（墙钟，与编辑器 performance.now()/1000 语义一致）
    private val animStartNanos: Long = System.nanoTime()

    // 贴图大小缩放因子：使用用户设置的 texSize / 16（基准 16px），用于控制贴图粒子的显示尺寸
    private val texScale: Float = computeTexScale()

    // 非均匀缩放：局部 X 轴（长）/ Y 轴（宽）两个半宽，单位 Minecraft 块
    private var scaleW: Float = 0f
    private var scaleH: Float = 0f

    // v17 非广告牌朝向：billboard=false 时四边形静止朝世界 +Z，再按 spin 旋转（spinLocal=局部轴）
    private var billboard = true
    private var spinDeg = DoubleArray(3)
    private var spinLocal = true

    // 朝向四元数与自转角一起缓存：顶点生成是每渲染帧每颗粒子都跑的热路径，
    // 自转只在同步时变，不能在那里现算三角函数
    private val orientQ = Quaternionf()
    private var orientDirty = true

    /** 同步朝向（广告牌/自转；billboard=true 时自转被忽略）。 */
    fun syncOrientation(billboard: Boolean, spin: DoubleArray, spinLocal: Boolean) {
        if (this.billboard != billboard || this.spinLocal != spinLocal) orientDirty = true
        else if (spin.size >= 3 && (spinDeg[0] != spin[0] || spinDeg[1] != spin[1] || spinDeg[2] != spin[2])) orientDirty = true
        this.billboard = billboard
        this.spinLocal = spinLocal
        if (spin.size >= 3) {
            this.spinDeg[0] = spin[0]; this.spinDeg[1] = spin[1]; this.spinDeg[2] = spin[2]
        }
    }

    /** 自转欧拉（度）→ 四元数（复用实例，只在自转变化后重算一次）。 */
    private fun orientationQuaternion(): Quaternionf {
        if (orientDirty) {
            orientationQuaternion(spinDeg, spinLocal, orientQ)
            orientDirty = false
        }
        return orientQ
    }

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

    // 世界坐标只读口：渲染分组做视锥剔除要用，而 Particle.x/y/z 是 protected，别的包读不到
    fun renderX(): Double = x
    fun renderY(): Double = y
    fun renderZ(): Double = z

    /** 更新 UV 参数（动画粒子 UV 为静态属性，通常只在 spawn 时设置一次）。 */
    fun setUv(uv: UvData?) {
        this.uv = uv
        this.texEntry = resolveTexture()
        this.layerCache = null
    }

    /** 解析当前 UV 指向的贴图（贴图在 spawn 前已由动画管理器预加载）。 */
    private fun resolveTexture(): TextureCache.Entry? {
        val tex = uv?.texture ?: return TextureCache.defaultWhite()
        return TextureCache.get(tex) ?: TextureCache.defaultWhite()
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
     * sx → quad 长边（四边形的局部 X 轴），sy → quad 短边（局部 Y 轴），sz 暂存数据不参与渲染。
     * @param scaleArray 三分量缩放数组
     */
    fun syncScaleArray(scaleArray: FloatArray) {
        scaleW = scaleArray[0] * EDITOR_TO_MC_SCALE * texScale
        scaleH = scaleArray[1] * EDITOR_TO_MC_SCALE * texScale
        quadSize = scaleW  // 兼容原版字段
    }

    /**
     * 返回 quad 高度（原版唯一的尺寸口，供 QuadParticleGroup 批量渲染使用）。
     * 宽度由 OrientedQuadRenderState 单独记着，顶点生成时按 (长, 宽) 各自缩放。
     */
    override fun getQuadSize(partialTick: Float): Float = scaleH

    /**
     * 重写 extractRotatedQuad：非广告牌时把相机朝向换成自转朝向；非等宽时把宽度交给渲染状态，
     * 顶点生成阶段再按 (长, 宽) 各自缩放。原版只认一个尺寸，走不出非等宽四边形。
     */
    override fun extractRotatedQuad(
        state: QuadParticleRenderState,
        camera: net.minecraft.client.Camera,
        rotation: Quaternionf,
        partialTick: Float
    ) {
        // 非广告牌：用自转四元数替换相机朝向旋转（四边形固定朝世界 +Z）
        val q = if (billboard) rotation else orientationQuaternion()
        // 非等宽：把宽度交给渲染状态，顶点生成时按 (长, 宽) 各自缩放（add() 里消费掉）
        if (state is OrientedQuadRenderState) state.pendingWidth = if (scaleW != scaleH) scaleW else -1f
        super.extractRotatedQuad(state, camera, q, partialTick)
    }

    override fun tick() {
        age++
        if (age >= lifetime) {
            remove()
        }
    }

    /**
     * 渲染分组缓存：混合模式与贴图没变时复用同一个 Layer 实例。
     * 顶点生成每帧每颗粒子都会取一次分组，原版实现每次都要新建 record 并重算哈希。
     */
    private var layerCache: Layer? = null
    private var layerTranslucent = false

    override fun getLayer(): Layer {
        val translucent = additive || alpha < 1.0f
        val cached = layerCache
        if (cached != null && translucent == layerTranslucent) return cached
        val built = if (additive) {
            // 加法混合：始终走半透明通道 + ADDITIVE_PARTICLE 管线。
            // texEntry 恒非空（resolveTexture 对无贴图回退 defaultWhite），取它的 atlas id。
            Layer(true, texEntry!!.id, ADDITIVE_PARTICLE)
        } else {
            val entry = texEntry
            if (entry != null) {
                Layer(translucent, entry.id, if (translucent) RenderPipelines.TRANSLUCENT_PARTICLE else RenderPipelines.OPAQUE_PARTICLE)
            } else if (translucent) {
                Layer.TRANSLUCENT
            } else {
                Layer.OPAQUE
            }
        }
        layerCache = built
        layerTranslucent = translucent
        return built
    }

    // 使用自定义分组（无 16384 上限），绕过原版 SINGLE_QUADS 的粒子数限制
    override fun getGroup(): ParticleRenderType = BATCHED_QUADS

    // —— UV 采样（贴图像素坐标 → 归一化 [0,1]） ——
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
        val u = uv ?: return 0f // 默认全白纹理：整图 UV
        if (u.mode == UvData.Mode.FILL) return 0f
        val sx = currentUvStart(entry.width, entry.height)[0]
        return (sx.toFloat() / entry.width).coerceIn(0f, 1f)
    }

    override fun getU1(): Float {
        val entry = texEntry ?: return super.getU1()
        val u = uv ?: return 1f // 默认全白纹理：整图 UV
        if (u.mode == UvData.Mode.FILL) return 1f
        val sx = currentUvStart(entry.width, entry.height)[0]
        val w = if (u.uvSize[0] > 0) u.uvSize[0] else entry.width
        return ((sx + w).toFloat() / entry.width).coerceIn(0f, 1f)
    }

    override fun getV0(): Float {
        val entry = texEntry ?: return super.getV0()
        val u = uv ?: return 0f // 默认全白纹理：整图 UV
        if (u.mode == UvData.Mode.FILL) return 0f
        val sy = currentUvStart(entry.width, entry.height)[1]
        val h = if (u.uvSize[1] > 0) u.uvSize[1] else entry.height
        // v 坐标直接取 sy/H：MC 纹理 v=0 对应图顶（行 0），无需再翻转
        return (sy.toFloat() / entry.height).coerceIn(0f, 1f)
    }

    override fun getV1(): Float {
        val entry = texEntry ?: return super.getV1()
        val u = uv ?: return 1f // 默认全白纹理：整图 UV
        if (u.mode == UvData.Mode.FILL) return 1f
        val sy = currentUvStart(entry.width, entry.height)[1]
        val h = if (u.uvSize[1] > 0) u.uvSize[1] else entry.height
        return ((sy + h).toFloat() / entry.height).coerceIn(0f, 1f)
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
         * 原版 quad 顶点把 scale 当「半宽」使用（±scale），而编辑器 aSize 是整宽，
         * 因此这里取编辑器 PARTICLE_SIZE_FACTOR(0.2) 的一半，使最终整宽一致。
         */
        const val EDITOR_TO_MC_SCALE: Float = 0.1f

        /**
         * 自转欧拉（度）→ 四元数写入 [out]。local = intrinsic XYZ（Rx·Ry·Rz）；world = extrinsic（Rz·Ry·Rx）。
         * 单独拆出来是为了能在不启动 Minecraft 的测试里验证朝向语义。
         */
        @JvmStatic
        fun orientationQuaternion(spinDeg: DoubleArray, spinLocal: Boolean, out: Quaternionf) {
            val rx = Math.toRadians(spinDeg[0]).toFloat()
            val ry = Math.toRadians(spinDeg[1]).toFloat()
            val rz = Math.toRadians(spinDeg[2]).toFloat()
            if (spinLocal) {
                out.rotationXYZ(rx, ry, rz)
            } else {
                out.rotationZ(rz).mul(Quaternionf().rotationY(ry)).mul(Quaternionf().rotationX(rx))
            }
        }

        /**
         * 无贴图粒子使用的默认精灵（原版粒子图集 generic_0），渲染时由颜色染色为纯色方块。
         */
        private fun defaultSprite(): TextureAtlasSprite {
            val atlas = Minecraft.getInstance()
                .atlasManager
                .getAtlasOrThrow(AtlasIds.PARTICLES)
            return atlas.getSprite(Identifier.withDefaultNamespace("generic_0"))
        }
    }
}
