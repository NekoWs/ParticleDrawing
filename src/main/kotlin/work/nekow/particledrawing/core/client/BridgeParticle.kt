package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.data.AtlasIds
import net.minecraft.util.Mth
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import java.util.UUID

/**
 * 连接渲染粒子与 Minecraft 粒子系统的桥接粒子。
 * 将自定义粒子的位置、颜色和缩放属性同步到原版渲染管线中。
 *
 * @param particleId 粒子唯一标识符
 * @param style 粒子样式
 * @param level 客户端世界实例
 * @param x 初始 X 坐标
 * @param y 初始 Y 坐标
 * @param z 初始 Z 坐标
 * @param color 初始颜色
 * @param scale 初始缩放
 * @param isGlowing 是否发光
 */
@Suppress("unused")
class BridgeParticle(
    val particleId: UUID,
    style: ParticleStyle,
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    color: Color,
    scale: Float,
    private var isGlowing: Boolean
) : SingleQuadParticle(level, x, y, z, getSpriteForStyle(style)) {

    init {
        xo = x
        yo = y
        zo = z

        setColor(color.r, color.g, color.b)
        alpha = color.a
        quadSize = scale
        lifetime = Int.MAX_VALUE
        gravity = 0f
        hasPhysics = false
    }

    fun isGlowing(): Boolean = isGlowing

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
     * 同步粒子缩放。
     * @param scale 目标缩放值
     */
    fun syncScale(scale: Float) {
        quadSize = scale
    }

    override fun tick() {
        age++
        if (age >= lifetime) {
            remove()
        }
    }

    override fun getLayer(): Layer {
        return if (alpha < 1.0f || sprite.transparency().hasTranslucent()) {
            Layer.TRANSLUCENT
        } else {
            Layer.OPAQUE
        }
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
        val bx = Mth.floor(this.x).toInt()
        val by = Mth.floor(this.y).toInt()
        val bz = Mth.floor(this.z).toInt()
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
