package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.resources.Identifier
import java.util.Optional
import java.util.OptionalDouble

/** 在地形与普通透明合成后提交使用场景纹理的粒子。 */
object SceneParticlePass {
    private val vertexFormat = SceneVertexFormat.builder(0)
        .addAttribute("Position", SceneGpuFormat.RGB32_FLOAT)
        .addAttribute("UV0", SceneGpuFormat.RG32_FLOAT)
        .addAttribute("Color", SceneGpuFormat.RGBA8_UNORM)
        .addAttribute("UV2", SceneGpuFormat.RG16_SINT)
        .addAttribute("UV1", SceneGpuFormat.RG16_SINT)
        .build()
    private val sceneLayout = sceneSamplerLayout()
    private val shader = Identifier.fromNamespaceAndPath("particledrawing", "core/scene_particle")
    private fun pipeline(name: String, blend: SceneBlendFunction): SceneRenderPipeline = SceneRenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("particledrawing", name))
        .withVertexShader(shader)
        .withFragmentShader(shader)
        .sceneDepthConvention()
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(sceneProjectionLayout)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withBindGroupLayout(sceneLayout)
        .withVertexBinding(0, vertexFormat)
        .withPrimitiveTopology(SceneTopology.QUADS)
        .withDepthStencilState(SceneDepthStencilState(SceneCompareOp.GREATER_THAN_OR_EQUAL, false))
        .withColorTargetState(SceneColorTargetState(blend))
        .withCull(false)
        .build()

    private val alpha by lazy { pipeline("scene_particle", SceneBlendFunction.TRANSLUCENT) }
    private val additive by lazy { pipeline("scene_particle_additive", SceneBlendFunction.LIGHTNING) }
    private val scene = ParticleSceneBuffers()
    private var vertices: StagedVertexBuffer? = null

    @JvmStatic fun render() {
        ParticleInstancePass.render()
        val batches = SceneParticleQuads.batches
        if (batches.isEmpty()) return
        val mc = Minecraft.getInstance()
        val target = mc.gameRenderer.mainRenderTarget()
        val staged = vertices ?: StagedVertexBuffer({ "Particle scene vertices" }, 16384).also { vertices = it }
        val draws = arrayListOf<Pair<SingleQuadParticle.Layer, StagedVertexBuffer.Draw>>()
        for ((material, state) in batches) {
            if (state.isEmpty) continue
            for (layer in state.layers()) {
                val draw = staged.appendDraw(vertexFormat, SceneTopology.QUADS, VertexSorting.DISTANCE_TO_ORIGIN)
                state.buildLayer(layer, SceneParticleVertices(staged.getVertexBuilder(draw), material))
                draws += layer to draw
            }
        }
        if (draws.isEmpty()) return
        staged.upload()
        scene.capture(target)
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy())
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(SceneFilterMode.LINEAR)
        RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "Particle scene materials" }, requireNotNull(target.colorTextureView), Optional.empty(),
            requireNotNull(target.depthTextureView), OptionalDouble.empty(),
        ).use { pass ->
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.bindSceneTexture("Sampler2", mc.gameRenderer.lightmap(), sampler)
            scene.bind(pass)
            for ((layer, draw) in draws) {
                val info = staged.getExecuteInfo(draw) ?: continue
                val pipeline = if (layer.pipeline() === ADDITIVE_PARTICLE) additive else alpha
                val texture = mc.textureManager.getTexture(layer.textureAtlasLocation())
                pass.useScenePipeline(pipeline)
                pass.bindSceneTexture("Sampler0", texture.textureView, texture.sampler)
                pass.setVertexBuffer(0, info.vertexBuffer().slice())
                pass.setIndexBuffer(info.indexBuffer(), info.indexType())
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0)
            }
        }
    }

    @JvmStatic fun endFrame() {
        ParticleInstancePass.endFrame()
        SceneParticleQuads.reset()
        vertices?.endFrame()
    }

    @JvmStatic fun close() {
        ParticleInstancePass.close()
        SceneParticleQuads.reset()
        vertices?.close()
        vertices = null
        scene.close()
    }
}
