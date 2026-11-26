package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector4f
import org.joml.Vector3f
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.api.ParticleRenderOptions
import work.nekow.particledrawing.api.ParticleTrailStyle
import work.nekow.particledrawing.animation.ParticleUvRegion
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.lighting.DynamicLightManager
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import work.nekow.particledrawing.render.RibbonHistory
import java.util.UUID
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
    private val uvRegion = ParticleUvRegion()

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
    internal var instanced = false
    private var recipeFrameHeld = false

    // 非广告牌朝向：billboard=false 时四边形静止朝世界 +Z，再按 spin 旋转（spinLocal=局部轴）
    private var billboard = true
    private val spinDeg = DoubleArray(3)
    private var spinLocal = true

    // 朝向四元数与自转角一起缓存：顶点生成是每渲染帧每颗粒子都跑的热路径
    private val orientQ = Quaternionf()
    private var orientDirty = true
    private var morphOrientation: MorphOrientation? = null

    internal fun yawAt(partialTick: Float): Double =
        morphOrientation?.yawAt(frameFactor(partialTick)) ?: if (billboard) 0.0 else spinDeg[1]

    internal fun pitchAt(partialTick: Float): Double =
        morphOrientation?.pitchAt(frameFactor(partialTick)) ?: if (billboard) 0.0 else spinDeg[0]

    internal fun syncMorphOrientation(yaw: Double?, pitch: Double?, snap: Boolean) {
        // 始终推进已有端点，未指定的轴保持当前位置。
        val orientation = morphOrientation ?: run {
            if (yaw == null && pitch == null) return
            MorphOrientation(yawAt(1f), pitchAt(1f)).also { morphOrientation = it }
        }
        orientation.set(yaw, pitch, snap)
    }

    /** 同步朝向（广告牌/自转；billboard=true 时自转被忽略）。 */
    fun syncOrientation(billboard: Boolean, spin: DoubleArray, spinLocal: Boolean) {
        if (this.billboard != billboard || this.spinLocal != spinLocal) orientDirty = true
        else if (spin.size >= 3 && (spinDeg[0] != spin[0] || spinDeg[1] != spin[1] || spinDeg[2] != spin[2])) orientDirty = true
        morphOrientation = null
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

    private var effects: ParticleRenderEffects? = null

    /** 同步粒子位置；snap = 跳变到目标位置，否则保留上一 tick 位置作为插值起点。 */

    internal var model: ParticleModelRenderer.Model? = null
    internal var sceneMaterial = work.nekow.particledrawing.api.ParticleSceneMaterial.NONE

    internal fun enableRenderEffects(visual: ParticleRenderOptions, previous: Vec3?, trailStyle: ParticleTrailStyle) {
        val state = ParticleRenderEffects(visual, previous ?: Vec3(x, y, z), trailStyle)
        effects = state
        if (previous != null) {
            state.syncPosition(previous.x, previous.y, previous.z, x, y, z, snap = false)
            state.record(x, y, z)
        }
    }

    internal var trailOwner: UUID? = null

    internal fun detachTrail(age: Int = 0): Boolean {
        if (effects?.detach(age) != true) return false
        xo = x; yo = y; zo = z
        blend.hold()
        return true
    }

    internal fun tickDetachedTrail() = effects?.tickDetached() == true
    internal fun setDetachedTrailPaused(paused: Boolean) { effects?.setDetachedPaused(paused) }

    internal fun recordTrail() {
        effects?.record(x, y, z)
    }

    internal fun syncTrail(history: RibbonHistory) {
        effects?.syncTrail(history)
    }

    internal fun holdRecipeFrame() {
        xo = x; yo = y; zo = z
        blend.hold()
        recipeFrameHeld = true
    }

    fun syncPosition(x: Double, y: Double, z: Double, snap: Boolean = false, resetEffects: Boolean = snap) {
        recipeFrameHeld = false
        effects?.syncPosition(this.x, this.y, this.z, x, y, z, resetEffects)
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
    fun syncScale(scale: Float, snap: Boolean = false) = syncScale(scale, scale, snap)

    internal fun syncScale(width: Float, height: Float, snap: Boolean) {
        baseScaleW = width
        baseScaleH = height
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

    private fun frameFactor(partialTick: Float): Float = if (recipeFrameHeld) 1f else partialTick.coerceIn(0f, 1f)

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

    internal fun instanceSample(partialTick: Float, out: ParticleInstanceDraws.Sample) {
        refreshTextureIfStale()
        val entry = texEntry ?: TextureCache.defaultWhite()
        uvRegion.updateFromTexture(entry, uv, animStartNanos, textureClock, age, frameFactor(partialTick))
        val start = frameFactor(0f)
        val end = frameFactor(1f)
        val effect = effects
        fun color(f: Float, destination: Vector4f) {
            destination.set(blend.redAt(f), blend.greenAt(f), blend.blueAt(f),
                blend.alphaAt(f) * (effect?.alpha(x, y, z, f) ?: 1f))
        }
        color(start, out.color0)
        color(end, out.color1)
        out.texture = entry.id
        out.model = model
        out.blend = when {
            additive -> ParticleInstanceDraws.Blend.ADDITIVE
            !entry.translucent && out.color0.w >= 1f && out.color1.w >= 1f -> ParticleInstanceDraws.Blend.OPAQUE
            else -> ParticleInstanceDraws.Blend.ALPHA
        }
        out.from.set(sampledX(0f), sampledY(0f), sampledZ(0f))
        out.to.set(sampledX(1f), sampledY(1f), sampledZ(1f))
        out.width0 = frameWidth(0f)
        out.height0 = frameHeight(0f)
        out.width1 = frameWidth(1f)
        out.height1 = frameHeight(1f)
        out.light = if (isGlowing) -1 else getLightColor(partialTick)
        out.orientation = effect?.orientation() ?: if (billboard) null else orientationQuaternion()
        out.uv.set(getU0(), getV0(), getU1(), getV1())
    }

    internal var recipeStrand: RecipeStrand? = null

    internal fun strandPosition(partialTick: Float) =
        CoreVec3(sampledX(partialTick), sampledY(partialTick), sampledZ(partialTick))

    internal fun strandTint(partialTick: Float, position: CoreVec3): Int {
        val f = frameFactor(partialTick)
        val opacity = effects?.alpha(position.x, position.y, position.z, f) ?: 1f
        return RibbonVertices.color(blend.redAt(f), blend.greenAt(f), blend.blueAt(f), blend.alphaAt(f) * opacity)
    }

    fun sampledAlpha(partialTick: Float): Float = blend.alphaAt(frameFactor(partialTick))
    fun sampledWidth(partialTick: Float): Float =
        blend.widthAt(frameFactor(partialTick)) * (effects?.stretchFactor ?: 1f)
    fun sampledHeight(partialTick: Float): Float =
        blend.heightAt(frameFactor(partialTick)) / (effects?.stretchFactor ?: 1f)

    override fun getQuadSize(partialTick: Float): Float = sampledHeight(partialTick)

    private fun frameWidth(partialTick: Float): Float = sampledWidth(partialTick)
    private fun frameHeight(partialTick: Float): Float = sampledHeight(partialTick)

    /**
     * 渲染剔除用的包围盒。
     *
     * 原版按 `getQuadSize()` 算半边长；尺寸为 0 或负值时包围盒退化成点/反向盒，会被视锥直接判掉，
     * 表现就是粒子存在但 `render()` 从不被调用。这里改用插值后的位置与长宽里较大的那一边，
     * 并兜一个最小半边长，保证盒子始终合法。
     */
    override fun getRenderBoundingBox(partialTicks: Float): net.minecraft.world.phys.AABB {
        recipeStrand?.bounds(this, partialTicks)?.let { return it }
        val half = maxOf(frameWidth(partialTicks), frameHeight(partialTicks), 0.5f, model?.fixedRadius ?: 0f)
        val px = sampledX(partialTicks)
        val py = sampledY(partialTicks)
        val pz = sampledZ(partialTicks)
        effects?.let { if (it.trail != null) return it.bounds(px, py, pz, half) }
        return net.minecraft.world.phys.AABB(px - half, py - half, pz - half, px + half, py + half, pz + half)
    }

    override fun tick() {
        if (textureClock?.recipeSuspended == true || effects?.trailOnly == true) return
        age++
        if (age >= lifetime) remove()
    }

    /**
     * 顶点生成前的最后一站：把颜色/透明度换成帧插值后的值。
     * 原版在 `renderRotatedQuad` 里直接读 `rCol/alpha`，没有 partialTick 口，只能临时替换、读完还原。
     */
    override fun render(consumer: VertexConsumer, camera: Camera, partialTick: Float) {
        if (deferredSceneMaterial() || postShaderAdditive() || (ParticleOitPass.enabled() && weightedAlpha(partialTick))) return
        renderGeometry(consumer, camera, partialTick)
    }

    internal fun deferredSceneMaterial(): Boolean = sceneMaterial.active &&
        ParticleDrawingConfig.CLIENT.sceneMaterials.get() && !ShaderCompat.legacyPipelines()

    internal fun postShaderAdditive(): Boolean = additive && ShaderCompat.postShaderParticles() &&
        ParticleDrawingConfig.CLIENT.customAdditivePipeline.get() && !usesInstancePipeline()

    internal fun weightedAlpha(partialTick: Float): Boolean {
        if (additive || usesInstancePipeline() || deferredSceneMaterial()) return false
        refreshTextureIfStale()
        return sampledAlpha(partialTick) < 1f || texEntry?.translucent == true || effects?.translucent == true
    }

    private fun usesInstancePipeline(): Boolean = instanced && !ShaderCompat.legacyPipelines()

    internal fun renderGeometry(consumer: VertexConsumer, camera: Camera, partialTick: Float) {
        if (lightOnly || textureClock?.recipeSuspended == true || effects?.trailPaused == true) return
        val f = frameFactor(partialTick)
        val cam = camera.position
        if (!ParticleRenderEffects.visible(particleId, cam, x, y, z)) return
        refreshTextureIfStale()
        val link = recipeStrand
        if (link != null) {
            texEntry?.let { uvRegion.updateFromTexture(it, uv, animStartNanos, textureClock, age, f) }
            ribbonVertices.clear()
            link.append(this, ribbonVertices, CoreVec3(cam.x, cam.y, cam.z), partialTick,
                (getU0() + getU1()) * 0.5f, getV0(), getV1(), getLightColor(f))
            ribbonVertices.writeTo(consumer)
            return
        }
        val entry = texEntry
        val effect = effects
        val trail = effect?.trail
        val trailOnly = effect?.trailOnly == true
        val submitInstances = usesInstancePipeline()
        if (submitInstances && !trailOnly) {
            var uvChanged = false
            val animated = entry != null && (entry.animation != null || uv?.mode == UvData.Mode.ANIMATED)
            if (animated) {
                val beforeU0 = getU0(); val beforeV0 = getV0()
                val beforeU1 = getU1(); val beforeV1 = getV1()
                uvRegion.updateFromTexture(entry, uv, animStartNanos, textureClock, age, f)
                uvChanged = beforeU0 != getU0() || beforeV0 != getV0() || beforeU1 != getU1() || beforeV1 != getV1()
            }
            val curved = textureClock?.hasAppearanceCurve() == true
            ParticleInstanceDraws.offer(this, animated || curved, uvChanged || curved)
            if (trail == null) return
        }
        entry?.let { uvRegion.updateFromTexture(it, uv, animStartNanos, textureClock, age, f) }
        val cr = rCol
        val cg = gCol
        val cb = bCol
        val ca = alpha
        if (f > 0f) {
            rCol = blend.redAt(f)
            gCol = blend.greenAt(f)
            bCol = blend.blueAt(f)
            alpha = blend.alphaAt(f)
        }
        alpha *= effect?.alpha(x, y, z, f) ?: 1f
        try {
            if (!submitInstances && !trailOnly && (alpha > 0f || effect == null)) super.render(consumer, camera, partialTick)
            if (trail != null) {
                ribbonVertices.clear()
                ribbonVertices.append(
                    trail, CoreVec3(sampledX(f), sampledY(f), sampledZ(f)),
                    CoreVec3(cam.x, cam.y, cam.z), frameHeight(f), if (trailOnly) 1f else f,
                    getU0(), getU1(), getV0(), getV1(), RibbonVertices.color(rCol, gCol, bCol, alpha), getLightColor(f),
                    effect.trailSides, effect.trailStyle, effect.trailOpacity(if (recipeFrameHeld) 0f else partialTick),
                )
                ribbonVertices.writeTo(consumer)
            }
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
        val q = effects?.orientation() ?: morphOrientation?.rotationAt(
            frameFactor(partialTick), if (billboard) 0.0 else spinDeg[2], billboard || spinLocal,
            if (billboard) rotation else null,
        ) ?: if (billboard) rotation else orientationQuaternion()
        val w = frameWidth(partialTick)
        val h = frameHeight(partialTick)
        model?.let { mesh ->
            val cam = camera.position
            val center = modelPosition.set(
                (sampledX(partialTick) - cam.x).toFloat(), (sampledY(partialTick) - cam.y).toFloat(), (sampledZ(partialTick) - cam.z).toFloat(),
            )
            ParticleModelRenderer.draw(mesh, consumer, center, q, w, h,
                getU0(), getU1(), getV0(), getV1(), RibbonVertices.color(rCol, gCol, bCol, alpha), getLightColor(partialTick))
            return
        }
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
        val translucent = additive || sceneMaterial.active || alpha < 1.0f || texEntry?.translucent == true || effects?.translucent == true
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

    @Suppress("DEPRECATION") // 保留 1.21.1 原版粒子贴图的回退路径。
    internal fun sceneTexture(): net.minecraft.resources.Identifier {
        refreshTextureIfStale()
        return texEntry?.id ?: net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_PARTICLES
    }

    internal val sceneAdditive: Boolean get() = additive

    // UV 采样：贴图像素坐标 → 归一化 [0,1]。GPU 纹理第 0 行 = PNG 顶部，不做翻转；
    // 原版 quad 上边缘取 v0、下边缘取 v1。

    override fun getU0(): Float = if (texEntry == null) 0f else uvRegion.u0
    override fun getU1(): Float = if (texEntry == null) 1f else uvRegion.u1
    override fun getV0(): Float = if (texEntry == null) 0f else uvRegion.v0
    override fun getV1(): Float = if (texEntry == null) 1f else uvRegion.v1

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
        private val modelPosition = Vector3f()
        private val ribbonVertices = RibbonVertices()
        /** 编辑器 → Minecraft 世界单位的缩放因子。 */
        const val EDITOR_TO_MC_SCALE: Float = VisualMath.EDITOR_TO_MC_SCALE

        /** 复用的角点缓冲：顶点生成是逐粒子逐帧的热路径。 */
        private val scratch = org.joml.Vector3f()

        /**
         * 自转欧拉（度）→ 四元数写入 [out]。local = intrinsic XYZ；world = extrinsic。
         */
        @JvmStatic
        fun orientationQuaternion(spinDeg: DoubleArray, spinLocal: Boolean, out: Quaternionf) =
            particleOrientationQuaternion(spinDeg, spinLocal, out)
    }
}
