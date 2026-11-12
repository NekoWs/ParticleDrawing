package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.lighting.DynamicLightManager
import work.nekow.particledrawing.util.VisualMath

/**
 * 1.21.1 的桥接粒子：把自定义粒子的位置/颜色/缩放同步进原版粒子管线。
 *
 * 与 26.x 版的差别只在渲染入口：1.21.1 的 `Particle.render(VertexConsumer, Camera, float)`
 * 直接把顶点写进 `ParticleEngine` 的缓冲，分组由 `getRenderType()` 决定。
 * 其余（贴图解析、UV 归一化、颜色/尺寸插值、动态光缓存）与 26.x 版语义一致。
 */
@Suppress("unused")
class BridgeParticle(
    val particleId: java.util.UUID,
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    color: Color,
    scale: Float,
    private var isGlowing: Boolean,
    private var uv: UvData? = null,
    private val additive: Boolean = false,
    private val textureClock: RenderParticle? = null,
    /** 只发光不画外观：不提交任何顶点，粒子本身仍在引擎里走时钟与光照收集。 */
    private val lightOnly: Boolean = false,
) : SingleQuadParticle(level, x, y, z) {

    // 贴图解析结果（贴图已注册时才非 null）：Identifier + 尺寸
    @Volatile
    private var texEntry: TextureCache.Entry? = resolveTexture()

    // flipbook 计时起点（墙钟）
    private val animStartNanos: Long = System.nanoTime()
    private var texturePartialTick = 0f

    // 贴图大小缩放因子：取景框最长边 / 16（基准 16px）。
    private var texScale: Float = texScaleFor()

    // 上次解析时的贴图表版本：版本一变（有新贴图注册/缓存被清）就重解析
    private var seenTexVersion: Int = TextureCache.version()

    // 非均匀缩放：局部 X 轴（长）/ Y 轴（宽）两个半宽，单位 Minecraft 块
    private var scaleW: Float = 0f
    private var scaleH: Float = 0f

    // 未乘 texScale 的编辑器单位尺寸：贴图到货后重算渲染尺寸要用它
    private var baseScaleW: Float = 0f
    private var baseScaleH: Float = 0f

    // 外观插值端点（尺寸 / 颜色 / 透明度）：渲染帧按 partialTick 在「上一 tick → 本 tick」间插值
    private val blend = AppearanceBlend()

    // 非广告牌朝向：billboard=false 时四边形静止朝世界 +Z，再按 spin 旋转（spinLocal=局部轴）
    private var billboard = true
    private val spinDeg = DoubleArray(3)
    private var spinLocal = true

    // 朝向四元数与自转角一起缓存：顶点生成是每渲染帧每颗粒子都跑的热路径
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

    private fun orientationQuaternion(): Quaternionf {
        if (orientDirty) {
            orientationQuaternion(spinDeg, spinLocal, orientQ)
            orientDirty = false
        }
        return orientQ
    }

    /** 贴图晚于粒子到货（运行时登记）时就地重解析。 */
    private fun refreshTextureIfStale() {
        val version = TextureCache.version()
        if (version == seenTexVersion) return
        seenTexVersion = version
        texEntry = resolveTexture()
        val scale = texScaleFor()
        if (scale != texScale) {
            texScale = scale
            applyScale(snap = true)
        }
        renderTypeCache = null
    }

    init {
        xo = x
        yo = y
        zo = z

        setColor(color.r, color.g, color.b)
        alpha = color.a
        baseScaleW = scale
        baseScaleH = scale
        scaleW = baseScaleW * EDITOR_TO_MC_SCALE * texScale
        scaleH = baseScaleH * EDITOR_TO_MC_SCALE * texScale
        quadSize = scaleW
        lifetime = Int.MAX_VALUE
        gravity = 0f
        hasPhysics = false
        blend.initialize(rCol, gCol, bCol, alpha, scaleW, scaleH)
    }

    fun isGlowing(): Boolean = isGlowing

    // 世界坐标只读口：Particle.x/y/z 是 protected
    fun renderX(): Double = x
    fun renderY(): Double = y
    fun renderZ(): Double = z

    /** 更新 UV 参数（动画粒子 UV 通常只在 spawn 时设置一次）。 */
    fun setUv(uv: UvData?) {
        this.uv = uv
        this.texEntry = resolveTexture()
        this.texScale = texScaleFor()
        this.seenTexVersion = TextureCache.version()
        this.renderTypeCache = null
        applyScale(snap = true)
    }

    private fun resolveTexture(): TextureCache.Entry? {
        val tex = uv?.texture ?: return TextureCache.defaultWhite()
        return TextureCache.get(tex) ?: TextureCache.defaultWhite()
    }

    /** 更新发光状态；发光与非发光走不同光照路径，缓存立即失效。 */
    fun setGlowing(glowing: Boolean) {
        if (isGlowing != glowing) cachedLight = -1
        isGlowing = glowing
    }

    /** 同步粒子位置；snap = 跳变到目标位置，否则保留上一 tick 位置作为插值起点。 */
    fun syncPosition(x: Double, y: Double, z: Double, snap: Boolean = false) {
        if (snap) {
            this.xo = x; this.yo = y; this.zo = z
        } else {
            this.xo = this.x; this.yo = this.y; this.zo = this.z
        }
        this.x = x; this.y = y; this.z = z
    }

    /** 同步颜色与透明度；渲染帧上的中间值在 [render] 里按 partialTick 插值。 */
    fun syncColor(r: Float, g: Float, b: Float, a: Float, snap: Boolean = false) {
        blend.setColor(r, g, b, a, snap)
        rCol = r; gCol = g; bCol = b; alpha = a
    }

    /** 同步标量缩放（均匀）。 */
    fun syncScale(scale: Float, snap: Boolean = false) {
        baseScaleW = scale
        baseScaleH = scale
        applyScale(snap)
    }

    /** 同步非均匀缩放 [sx, sy, sz]：sx → 长边，sy → 短边，sz 不参与渲染。 */
    fun syncScaleArray(scaleArray: FloatArray, snap: Boolean = false) {
        baseScaleW = scaleArray[0]
        baseScaleH = scaleArray[1]
        applyScale(snap)
    }

    /** 按当前 texScale 把编辑器单位尺寸落到渲染尺寸与插值两端点上。 */
    private fun applyScale(snap: Boolean) {
        val w = baseScaleW * EDITOR_TO_MC_SCALE * texScale
        val h = baseScaleH * EDITOR_TO_MC_SCALE * texScale
        blend.setScale(w, h, snap)
        scaleW = w
        scaleH = h
        quadSize = w
    }

    /** 贴图尺寸系数：取景框已知按取景框，整图且贴图未到货按已到货贴图的尺寸，都没有按 16 计。 */
    private fun texScaleFor(): Float {
        val entry = uv?.texture?.let { TextureCache.get(it) }
        return ParticleVisual.texScaleOf(uv, entry?.width ?: 0, entry?.height ?: 0)
    }

    private fun frameFactor(partialTick: Float): Float = partialTick.coerceIn(0f, 1f)

    fun sampledX(partialTick: Float): Double {
        val f = frameFactor(partialTick).toDouble()
        return xo + (x - xo) * f
    }

    fun sampledY(partialTick: Float): Double {
        val f = frameFactor(partialTick).toDouble()
        return yo + (y - yo) * f
    }

    fun sampledZ(partialTick: Float): Double {
        val f = frameFactor(partialTick).toDouble()
        return zo + (z - zo) * f
    }

    fun sampledAlpha(partialTick: Float): Float = blend.alphaAt(frameFactor(partialTick))
    fun sampledWidth(partialTick: Float): Float = blend.widthAt(frameFactor(partialTick))
    fun sampledHeight(partialTick: Float): Float = blend.heightAt(frameFactor(partialTick))

    override fun getQuadSize(partialTick: Float): Float = blend.heightAt(frameFactor(partialTick))

    private fun frameWidth(partialTick: Float): Float = blend.widthAt(frameFactor(partialTick))
    private fun frameHeight(partialTick: Float): Float = blend.heightAt(frameFactor(partialTick))

    /**
     * 渲染剔除用的包围盒。
     *
     * 原版按 `getQuadSize()` 算半边长；尺寸为 0 或负值时包围盒退化成点/反向盒，会被视锥直接判掉，
     * 表现就是粒子存在但 `render()` 从不被调用。这里改用插值后的位置与长宽里较大的那一边，
     * 并兜一个最小半边长，保证盒子始终合法。
     */
    override fun getRenderBoundingBox(partialTicks: Float): net.minecraft.world.phys.AABB {
        val half = maxOf(frameWidth(partialTicks), frameHeight(partialTicks), 0.5f)
        val px = sampledX(partialTicks)
        val py = sampledY(partialTicks)
        val pz = sampledZ(partialTicks)
        return net.minecraft.world.phys.AABB(px - half, py - half, pz - half, px + half, py + half, pz + half)
    }

    override fun tick() {
        age++
        if (age >= lifetime) remove()
    }

    /**
     * 顶点生成前的最后一站：把颜色/透明度换成帧插值后的值。
     * 原版在 `renderRotatedQuad` 里直接读 `rCol/alpha`，没有 partialTick 口，只能临时替换、读完还原。
     */
    override fun render(consumer: VertexConsumer, camera: Camera, partialTick: Float) {
        if (lightOnly) return
        val f = frameFactor(partialTick)
        texturePartialTick = f
        val cr = rCol; val cg = gCol; val cb = bCol; val ca = alpha
        if (f > 0f) {
            rCol = blend.redAt(f)
            gCol = blend.greenAt(f)
            bCol = blend.blueAt(f)
            alpha = blend.alphaAt(f)
        }
        try {
            super.render(consumer, camera, partialTick)
        } finally {
            rCol = cr; gCol = cg; bCol = cb; alpha = ca
        }
    }

    /**
     * 非广告牌时用自转四元数替换相机朝向；长宽不等时自己写四个顶点（原版只认单一半宽）。
     */
    override fun renderRotatedQuad(
        consumer: VertexConsumer,
        camera: Camera,
        rotation: Quaternionf,
        partialTick: Float,
    ) {
        val q = if (billboard) rotation else orientationQuaternion()
        val w = frameWidth(partialTick)
        val h = frameHeight(partialTick)
        if (w == h) {
            super.renderRotatedQuad(consumer, camera, q, partialTick)
            return
        }
        val cam = camera.position
        val px = (Mth.lerp(partialTick.toDouble(), xo, x) - cam.x).toFloat()
        val py = (Mth.lerp(partialTick.toDouble(), yo, y) - cam.y).toFloat()
        val pz = (Mth.lerp(partialTick.toDouble(), zo, z) - cam.z).toFloat()
        val u0 = getU0(); val u1 = getU1(); val v0 = getV0(); val v1 = getV1()
        val light = getLightColor(partialTick)
        // 四角顺序与原版一致：(+,-) (+,+) (-,+) (-,-)，长边走 X、短边走 Y
        emitCorner(consumer, q, px, py, pz, 1f, -1f, w, h, u1, v1, light)
        emitCorner(consumer, q, px, py, pz, 1f, 1f, w, h, u1, v0, light)
        emitCorner(consumer, q, px, py, pz, -1f, 1f, w, h, u0, v0, light)
        emitCorner(consumer, q, px, py, pz, -1f, -1f, w, h, u0, v1, light)
    }

    private fun emitCorner(
        consumer: VertexConsumer, q: Quaternionf,
        px: Float, py: Float, pz: Float,
        nx: Float, ny: Float, w: Float, h: Float,
        u: Float, v: Float, light: Int,
    ) {
        scratch.set(nx * w, ny * h, 0f).rotate(q)
        consumer.addVertex(scratch.x + px, scratch.y + py, scratch.z + pz)
            .setUv(u, v)
            .setColor(rCol, gCol, bCol, alpha)
            .setLight(light)
    }

    /**
     * 渲染分组：贴图与混合方式相同的粒子共用一个实例，原版按实例归组，一次 draw 画完。
     */
    private var renderTypeCache: ParticleRenderType? = null
    private var renderTypeTranslucent = false

    override fun getRenderType(): ParticleRenderType {
        refreshTextureIfStale()
        val translucent = additive || alpha < 1.0f
        val cached = renderTypeCache
        if (cached != null && translucent == renderTypeTranslucent) return cached
        val entry = texEntry
        val built = if (entry == null) {
            if (translucent) ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT else ParticleRenderType.PARTICLE_SHEET_OPAQUE
        } else {
            particleRenderType(entry.id, translucent, additive)
        }
        renderTypeCache = built
        renderTypeTranslucent = translucent
        return built
    }

    // UV 采样：贴图像素坐标 → 归一化 [0,1]。GPU 纹理第 0 行 = PNG 顶部，不做翻转；
    // 原版 quad 上边缘取 v0、下边缘取 v1。

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
                val cols = if (stepx > 0 && sx < texW) (texW - 1 - sx) / stepx + 1 else 1
                if (cols > 0) {
                    sx += (frame % cols) * stepx
                    sy += (frame / cols) * stepy
                }
            }
        }
        texEntry?.animation?.let { animation ->
            val ageTicks = textureClock?.ageTicks(texturePartialTick)?.toDouble() ?: (age + texturePartialTick).toDouble()
            sy += animation.frameAt(ageTicks, textureClock?.lifetimeTicks() ?: -1) * animation.frameHeight
        }
        return intArrayOf(sx, sy)
    }

    override fun getU0(): Float {
        val entry = texEntry ?: return 0f
        val u = uv ?: return 0f
        if (u.mode == UvData.Mode.FILL) return 0f
        return (currentUvStart(entry.width, entry.height)[0].toFloat() / entry.width).coerceIn(0f, 1f)
    }

    override fun getU1(): Float {
        val entry = texEntry ?: return 1f
        val u = uv ?: return 1f
        if (u.mode == UvData.Mode.FILL) return 1f
        val sx = currentUvStart(entry.width, entry.height)[0]
        val w = if (u.uvSize[0] > 0) u.uvSize[0] else entry.width
        return ((sx + w).toFloat() / entry.width).coerceIn(0f, 1f)
    }

    override fun getV0(): Float {
        val entry = texEntry ?: return 0f
        val u = uv ?: return 0f
        if (u.mode == UvData.Mode.FILL) return 0f
        val sy = currentUvStart(entry.width, entry.height)[1]
        return (sy.toFloat() / entry.height).coerceIn(0f, 1f)
    }

    override fun getV1(): Float {
        val entry = texEntry ?: return 1f
        val u = uv ?: return 1f
        if (u.mode == UvData.Mode.FILL) return 1f
        val sy = currentUvStart(entry.width, entry.height)[1]
        val h = if (u.uvSize[1] > 0) u.uvSize[1] else entry.animation?.frameHeight ?: entry.height
        return ((sy + h).toFloat() / entry.height).coerceIn(0f, 1f)
    }

    // 光照查询缓存：原版每渲染帧都要查世界光照，粒子在同一方块内可复用缓存。
    private var cachedLight = -1
    private var cacheBX = Int.MIN_VALUE
    private var cacheBY = Int.MIN_VALUE
    private var cacheBZ = Int.MIN_VALUE
    private var cacheLightVersion = Int.MIN_VALUE
    private var lightDueNanos = 0L

    override fun getLightColor(partialTick: Float): Int {
        if (isGlowing) return 0x00F000F0
        val bx = Mth.floor(this.x)
        val by = Mth.floor(this.y)
        val bz = Mth.floor(this.z)
        val now = System.nanoTime()
        val lightVersion = DynamicLightManager.version()
        val query = LightCachePolicy.shouldQuery(
            hasCache = cachedLight >= 0,
            blockChanged = bx != cacheBX || by != cacheBY || bz != cacheBZ,
            lightVersionChanged = lightVersion != cacheLightVersion,
            dueNanos = lightDueNanos,
            nowNanos = now,
        )
        if (query) {
            cachedLight = super.getLightColor(partialTick)
            cacheBX = bx; cacheBY = by; cacheBZ = bz
            cacheLightVersion = lightVersion
            lightDueNanos = LightCachePolicy.nextDueNanos(now, particleId.hashCode())
        }
        return cachedLight
    }

    companion object {
        /** 编辑器 → Minecraft 世界单位的缩放因子。 */
        const val EDITOR_TO_MC_SCALE: Float = VisualMath.EDITOR_TO_MC_SCALE

        /** 复用的角点缓冲：顶点生成是逐粒子逐帧的热路径。 */
        private val scratch = org.joml.Vector3f()

        /**
         * 自转欧拉（度）→ 四元数写入 [out]。local = intrinsic XYZ；world = extrinsic。
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
    }
}
