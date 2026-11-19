package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.systems.RenderSystem

/** 场景的只读副本可在写回主缓冲时绑定为纹理。 */
internal class ParticleSceneBuffers : AutoCloseable {
    private var snapshot: TextureTarget? = null

    fun capture(source: RenderTarget) {
        val color = requireNotNull(source.colorTexture)
        val depth = requireNotNull(source.depthTexture)
        val current = snapshot
        val target = if (current != null && current.width == source.width && current.height == source.height &&
            current.colorTexture?.format == color.format && current.depthTexture?.format == depth.format
        ) current else {
            current?.destroyBuffers()
            TextureTarget("Particle scene", source.width, source.height, true, source.useStencil, color.format).also { snapshot = it }
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
            color, requireNotNull(target.colorTexture), 0, 0, 0, 0, 0, source.width, source.height,
        )
        target.copyDepthFrom(source)
    }

    fun bind(pass: RenderPass) {
        val target = requireNotNull(snapshot)
        val samplers = RenderSystem.getSamplerCache()
        pass.bindTexture("SceneDepth", requireNotNull(target.depthTextureView), samplers.getClampToEdge(FilterMode.NEAREST))
        pass.bindTexture("SceneColor", requireNotNull(target.colorTextureView), samplers.getClampToEdge(FilterMode.LINEAR))
    }

    override fun close() {
        snapshot?.destroyBuffers()
        snapshot = null
    }
}
