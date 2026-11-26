package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.resources.Identifier
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterShadersEvent
import org.lwjgl.opengl.GL30
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.config.ParticleDrawingConfig

@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
internal object ParticleOitPass {
    private var accumulate: ShaderInstance? = null
    private var composite: ShaderInstance? = null
    private var accumulation: TextureTarget? = null
    private var vertices: ByteBufferBuilder? = null

    @SubscribeEvent
    @JvmStatic fun register(event: RegisterShadersEvent) {
        val accumulateId = Identifier.fromNamespaceAndPath(ParticleDrawing.MODID, "oit_accumulate")
        val compositeId = Identifier.fromNamespaceAndPath(ParticleDrawing.MODID, "oit_composite")
        event.registerShader(ShaderInstance(event.resourceProvider, accumulateId, DefaultVertexFormat.PARTICLE)) { accumulate = it }
        event.registerShader(ShaderInstance(event.resourceProvider, compositeId, DefaultVertexFormat.POSITION)) { composite = it }
    }

    fun enabled(): Boolean = accumulate != null && composite != null &&
        ParticleDrawingConfig.CLIENT.weightedParticleTransparency.get() && !ShaderCompat.legacyPipelines()

    fun render(camera: Camera, partialTick: Float, frustum: Frustum, lightmap: LightTexture) {
        if (!enabled()) return
        val engine = ClientParticleEngine.instance() ?: return
        val batches = linkedMapOf<Identifier, MutableList<BridgeParticle>>()
        engine.forEachBridgeParticle { particle ->
            if (particle.weightedAlpha(partialTick) && frustum.isVisible(particle.getRenderBoundingBox(partialTick))) {
                batches.getOrPut(particle.sceneTexture()) { arrayListOf() }.add(particle)
            }
        }
        if (batches.isEmpty()) return

        val source = Minecraft.getInstance().mainRenderTarget
        val previousShader = RenderSystem.getShader()
        val previousTexture = RenderSystem.getShaderTexture(0)
        val previousLightmap = RenderSystem.getShaderTexture(2)
        val vertexBuffer = vertices ?: ByteBufferBuilder(16384).also { vertices = it }
        try {
            val target = bindAccumulation(source)
            RenderSystem.setShader { accumulate }
            lightmap.turnOnLightLayer()
            RenderSystem.enableBlend()
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE)
            RenderSystem.disableCull()
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(false)
            for ((texture, particles) in batches) {
                RenderSystem.setShaderTexture(0, texture)
                val builder = BufferBuilder(vertexBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE)
                for (particle in particles) particle.renderGeometry(builder, camera, partialTick)
                builder.build()?.let(BufferUploader::drawWithShader)
            }

            source.bindWrite(true)
            RenderSystem.setShader { composite }
            RenderSystem.setShaderTexture(0, target.colorTextureId)
            RenderSystem.disableDepthTest()
            RenderSystem.defaultBlendFunc()
            RenderSystem.colorMask(true, true, true, false)
            val fullscreen = BufferBuilder(vertexBuffer, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION)
            fullscreen.addVertex(-1f, -1f, 0f)
            fullscreen.addVertex(3f, -1f, 0f)
            fullscreen.addVertex(-1f, 3f, 0f)
            BufferUploader.drawWithShader(fullscreen.buildOrThrow())
        } finally {
            source.bindWrite(true)
            RenderSystem.setShader { previousShader }
            RenderSystem.setShaderTexture(0, previousTexture)
            RenderSystem.setShaderTexture(2, previousLightmap)
            RenderSystem.colorMask(true, true, true, true)
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(true)
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableBlend()
            RenderSystem.enableCull()
        }
    }

    private fun bindAccumulation(source: RenderTarget): TextureTarget {
        val current = accumulation
        val target = if (current?.width == source.width && current.height == source.height) current else {
            current?.destroyBuffers()
            TextureTarget(source.width, source.height, false, Minecraft.ON_OSX).also {
                accumulation = it
                GlStateManager._bindTexture(it.colorTextureId)
                GlStateManager._texImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, it.width, it.height, 0,
                    GL30.GL_RGBA, GL30.GL_FLOAT, null)
            }
        }
        target.bindWrite(true)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_TEXTURE_2D, source.depthTextureId, 0)
        target.checkStatus()
        GL30.glClearBufferfv(GL30.GL_COLOR, 0, clearColor)
        return target
    }

    private val clearColor = FloatArray(4)

    fun close() {
        vertices?.close()
        vertices = null
        accumulation?.destroyBuffers()
        accumulation = null
    }
}
