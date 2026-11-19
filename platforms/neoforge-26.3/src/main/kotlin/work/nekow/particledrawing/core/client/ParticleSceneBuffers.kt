package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.textures.FilterMode
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
            TextureTarget("Particle scene", source.width, source.height, color.format, depth.format).also { snapshot = it }
        }
        target.copyColorFrom(source)
        target.copyDepthFrom(source)
    }

    fun bind(pass: RenderPass) {
        val target = requireNotNull(snapshot)
        val samplers = RenderSystem.getSamplerCache()
        pass.setUniform("SceneDepth", requireNotNull(target.depthTextureView), samplers.getClampToEdge(FilterMode.NEAREST))
        pass.setUniform("SceneColor", requireNotNull(target.colorTextureView), samplers.getClampToEdge(FilterMode.LINEAR))
    }

    override fun close() {
        snapshot?.destroyBuffers()
        snapshot = null
    }
}
