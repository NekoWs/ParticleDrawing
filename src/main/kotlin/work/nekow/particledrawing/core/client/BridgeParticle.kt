package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.data.AtlasIds
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import java.util.UUID

@Suppress("unused")
class BridgeParticle(
    val particleId: UUID,
    style: ParticleStyle,
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    color: Color,
    scale: Float,
    private val isGlowing: Boolean
) : SingleQuadParticle(level, x, y, z, getSpriteForStyle(style)) {

    init {
        xo = x
        yo = y
        zo = z

        setColor(color.r, color.g, color.b)
        alpha = if (isGlowing) 0f else color.a
        quadSize = scale
        lifetime = Int.MAX_VALUE
        gravity = 0f
        hasPhysics = false
    }

    fun isGlowing(): Boolean = isGlowing

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

    fun syncColor(r: Float, g: Float, b: Float, a: Float) {
        rCol = r
        gCol = g
        bCol = b
        alpha = if (isGlowing) 0f else a
    }

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

    override fun getLightCoords(partialTick: Float): Int {
        if (isGlowing) {
            return 0x00F000F0
        }
        return super.getLightCoords(partialTick)
    }

    companion object {
        private fun getSpriteForStyle(style: ParticleStyle): TextureAtlasSprite {
            val atlas = Minecraft.getInstance()
                .atlasManager
                .getAtlasOrThrow(AtlasIds.PARTICLES)
            return atlas.getSprite(style.spriteLocation)
        }
    }
}
