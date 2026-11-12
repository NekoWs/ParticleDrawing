package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.resources.Identifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.21.1 的粒子渲染分组。
 *
 * 26.x 里这是可编程的 `RenderPipeline`；1.21.1 的 `ParticleRenderType.begin(...)` 直接做状态设置
 * 并返回 `BufferBuilder`，原版按返回的实例把粒子归组，同组一次 draw。
 *
 * 所以这里按 (贴图, 混合方式) 缓存实例：贴图相同、混合相同的粒子落到同一个分组，
 * 才能一次画完；每个粒子都 new 一个的话会退化成逐粒子一次 draw。
 */
private val renderTypes = ConcurrentHashMap<String, ParticleRenderType>()

/**
 * 取（或建立）与贴图和混合方式对应的分组。
 *
 * 加法混合用 `(SRC_ALPHA, ONE)`：粒子着色器输出直通 alpha（texture × vertexColor，无预乘），
 * 而预乘的 `(ONE, ONE)` 会让 alpha=0.3 的粒子以满亮度相加，与编辑器预览不一致。
 */
fun particleRenderType(texture: Identifier, translucent: Boolean, additive: Boolean): ParticleRenderType {
    val key = texture.toString() + '/' + (if (additive) "add" else if (translucent) "trans" else "opaque")
    return renderTypes.computeIfAbsent(key) { PdParticleRenderType(texture, translucent, additive) }
}

private class PdParticleRenderType(
    private val texture: Identifier,
    private val translucent: Boolean,
    private val additive: Boolean,
) : ParticleRenderType {

    override fun begin(tesselator: Tesselator, textureManager: TextureManager): BufferBuilder {
        RenderSystem.setShader(GameRenderer::getParticleShader)
        RenderSystem.setShaderTexture(0, texture)
        if (additive) {
            RenderSystem.enableBlend()
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
            RenderSystem.depthMask(false)
        } else if (translucent) {
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.depthMask(false)
        } else {
            RenderSystem.disableBlend()
            RenderSystem.depthMask(true)
        }
        return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE)
    }

    override fun toString(): String = "particledrawing:$texture"
}
