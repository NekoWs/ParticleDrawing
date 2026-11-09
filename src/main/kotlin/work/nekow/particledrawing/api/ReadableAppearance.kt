package work.nekow.particledrawing.api

// 命名外观门面：把“世界尺寸 / 全亮 / 对外照明 / 混合 / 朝向”一次写清，
// 单颗、批量、Draw 与发射器都还是同一份 ParticleVisual，不新增渲染模型。

/** 对外照明；与全亮无关。 */
class Light private constructor(
    val level: Int,
    val intensity: Float,
    val emits: Boolean,
) {

    override fun toString(): String =
        if (!emits) "Light.none" else "Light(level=$level, intensity=$intensity)"

    companion object {
        @JvmStatic
        fun none(): Light = Light(0, 1f, false)

        /** 向外照明；[level] 为 0 时等价 none。 */
        @JvmStatic
        @JvmOverloads
        fun level(level: Int, intensity: Float = 1f): Light {
            require(level in 0..15) { "光照等级必须在 0..15" }
            require(intensity.isFinite() && intensity >= 0f) { "光强乘数必须是非负有限值" }
            return Light(level, intensity, level > 0)
        }
    }
}

/** 混合方式。 */
enum class Blend {
    ALPHA,
    ADDITIVE,
}

/** 片元朝向。 */
enum class Facing {
    BILLBOARD,
    FIXED,
}

/**
 * 外观构建器。Kotlin 用 [particleVisual]，Java 用 [ParticleAppearance.builder]。
 *
 * 默认值保持旧 API：不发光、不投光、广告牌、普通 alpha 混合。
 */
class ParticleVisualBuilder {

    var style: ParticleStyle? = null
    var texture: String? = null
    var uv: FloatArray? = null
    var worldWidth: Length? = null
    var worldHeight: Length? = null
    var fullBright: Boolean = false
    var light: Light = Light.none()
    var blend: Blend = Blend.ALPHA
    var facing: Facing = Facing.BILLBOARD
    var spin: Angle = Angle.radians(0.0)
    var spinLocal: Boolean = true

    /** 世界格尺寸；只给宽度时高度同宽。 */
    fun worldSize(width: Length, height: Length = width): ParticleVisualBuilder {
        width.requirePositive("外观宽度")
        height.requirePositive("外观高度")
        worldWidth = width
        worldHeight = height
        return this
    }

    /** 贴图 / 子矩形 UV。 */
    fun texture(name: String): ParticleVisualBuilder {
        texture = name
        style = null
        return this
    }

    /** 复用资源包中粒子的指定帧贴图。 */
    @JvmOverloads
    fun particleTexture(particle: String, frame: Int = 0): ParticleVisualBuilder = texture(ParticleTextures.of(particle, frame))

    fun uv(u0: Float, v0: Float, u1: Float, v1: Float): ParticleVisualBuilder {
        uv = floatArrayOf(u0, v0, u1, v1)
        return this
    }

    fun style(style: ParticleStyle): ParticleVisualBuilder {
        this.style = style
        texture = null
        return this
    }

    fun fullBright(enabled: Boolean): ParticleVisualBuilder {
        fullBright = enabled
        return this
    }

    fun light(light: Light): ParticleVisualBuilder {
        this.light = light
        return this
    }

    fun blend(blend: Blend): ParticleVisualBuilder {
        this.blend = blend
        return this
    }

    fun facing(facing: Facing): ParticleVisualBuilder {
        this.facing = facing
        return this
    }

    fun spin(angle: Angle, local: Boolean = true): ParticleVisualBuilder {
        this.spin = angle
        this.spinLocal = local
        return this
    }

    fun build(): ParticleVisual {
        require(style == null || texture == null) { "style 与 texture 只能选一种" }
        val visual = ParticleVisual()
        style?.let { visual.style(it) }
        texture?.let { visual.texture(it) }
        uv?.let { visual.uv(it[0], it[1], it[2], it[3]) }
        val w = worldWidth
        val h = worldHeight
        if (w != null && h != null) {
            visual.anisoWorld(w.blocks.toFloat(), h.blocks.toFloat())
        }
        visual.glowing = fullBright
        visual.lightLevel = light.level
        visual.lightIntensity = light.intensity
        visual.emitsLight = light.emits
        visual.additive = blend == Blend.ADDITIVE
        visual.billboard = facing == Facing.BILLBOARD
        visual.spinXDeg = 0.0
        visual.spinYDeg = 0.0
        visual.spinZDeg = Math.toDegrees(spin.radians)
        visual.spinLocal = spinLocal
        return visual
    }
}

fun particleVisual(block: ParticleVisualBuilder.() -> Unit): ParticleVisual =
    ParticleVisualBuilder().apply(block).build()

/** Java 静态入口。 */
object ParticleAppearance {
    @JvmStatic
    fun builder(): ParticleVisualBuilder = ParticleVisualBuilder()

    @JvmStatic
    fun create(): ParticleVisual = ParticleVisual()

    @JvmStatic
    fun lightNone(): Light = Light.none()

    @JvmStatic
    @JvmOverloads
    fun light(level: Int, intensity: Float = 1f): Light = Light.level(level, intensity)
}
