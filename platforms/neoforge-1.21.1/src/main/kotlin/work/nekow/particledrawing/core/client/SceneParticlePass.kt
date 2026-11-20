package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import com.mojang.blaze3d.vertex.VertexSorting
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
import work.nekow.particledrawing.ParticleDrawing

/** 在地形与普通透明合成后提交使用场景纹理的粒子。 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object SceneParticlePass {
    private val format = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION)
        .add("UV0", VertexFormatElement.UV0)
        .add("Color", VertexFormatElement.COLOR)
        .add("UV2", VertexFormatElement.UV2)
        .add("UV1", VertexFormatElement.UV1)
        .build()
    private var shader: ShaderInstance? = null
    private val scene = ParticleSceneBuffers()
    private var vertices: ByteBufferBuilder? = null

    @SubscribeEvent
    @JvmStatic fun register(event: RegisterShadersEvent) {
        event.registerShader(ShaderInstance(event.resourceProvider, Identifier.fromNamespaceAndPath(ParticleDrawing.MODID, "scene_particle"), format)) {
            shader = it
        }
    }

    private data class Batch(val texture: Identifier, val additive: Boolean)

    @JvmStatic fun render(camera: Camera, partialTick: Float, frustum: Frustum, lightmap: LightTexture) {
        ParticleInstancePass.render(camera.position, lightmap)
        val activeShader = shader ?: return
        val engine = ClientParticleEngine.instance() ?: return
        val batches = linkedMapOf<Batch, MutableList<BridgeParticle>>()
        engine.forEachSceneParticle { particle ->
            if (frustum.isVisible(particle.getRenderBoundingBox(partialTick))) {
                val batch = Batch(particle.sceneTexture(), particle.sceneAdditive)
                batches.getOrPut(batch) { arrayListOf() }.add(particle)
            }
        }
        if (batches.isEmpty()) return
        scene.capture(Minecraft.getInstance().mainRenderTarget)
        val buffer = vertices ?: ByteBufferBuilder(16384).also { vertices = it }
        val previousShader = RenderSystem.getShader()
        val previousTexture = RenderSystem.getShaderTexture(0)
        val previousLightmap = RenderSystem.getShaderTexture(2)
        RenderSystem.setShader { activeShader }
        lightmap.turnOnLightLayer()
        scene.bind(activeShader)
        RenderSystem.enableBlend()
        RenderSystem.disableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(false)
        try {
            for ((batch, particles) in batches) {
                if (batch.additive) RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
                else RenderSystem.defaultBlendFunc()
                RenderSystem.setShaderTexture(0, batch.texture)
                val builder = BufferBuilder(buffer, VertexFormat.Mode.QUADS, format)
                for (particle in particles) particle.render(SceneParticleVertices(builder, particle.sceneMaterial), camera, partialTick)
                val mesh = builder.build() ?: continue
                mesh.sortQuads(buffer, VertexSorting.DISTANCE_TO_ORIGIN)
                BufferUploader.drawWithShader(mesh)
            }
        } finally {
            RenderSystem.setShader { previousShader }
            RenderSystem.setShaderTexture(0, previousTexture)
            RenderSystem.setShaderTexture(2, previousLightmap)
            RenderSystem.depthMask(true)
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableBlend()
            RenderSystem.enableCull()
        }
    }

    @JvmStatic fun close() {
        ParticleInstancePass.close()
        vertices?.close()
        vertices = null
        scene.close()
    }
}
