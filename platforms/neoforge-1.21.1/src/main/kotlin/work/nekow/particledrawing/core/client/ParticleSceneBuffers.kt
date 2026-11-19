package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import org.lwjgl.opengl.GL30

/** 场景的只读副本可在写回主缓冲时绑定为纹理。 */
internal class ParticleSceneBuffers : AutoCloseable {
    private var snapshot: TextureTarget? = null

    fun capture(source: RenderTarget) {
        val current = snapshot
        val target = if (current != null && current.width == source.width && current.height == source.height) current else {
            current?.destroyBuffers()
            TextureTarget(source.width, source.height, true, Minecraft.ON_OSX).also { snapshot = it }
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId)
        GlStateManager._glBlitFrameBuffer(
            0, 0, source.width, source.height, 0, 0, target.width, target.height, GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST,
        )
        target.copyDepthFrom(source)
        source.bindWrite(false)
    }

    fun bind(shader: ShaderInstance) {
        val target = requireNotNull(snapshot)
        shader.setSampler("SceneDepth", target.depthTextureId)
        shader.setSampler("SceneColor", target.colorTextureId)
    }

    override fun close() {
        snapshot?.destroyBuffers()
        snapshot = null
    }
}
