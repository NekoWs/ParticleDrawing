package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.resources.Identifier
import org.joml.Vector4f
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.config.ParticleDrawingConfig
import java.util.Optional
import java.util.OptionalDouble

/** 半透明粒子的颜色和透射率用浮点加法累积，再一次性合到场景。 */
internal object ParticleOitPass {
    private val quads = OrientedQuadRenderState()
    private val format = SceneGpuFormat.RGBA16_FLOAT
    private val clear = Vector4f(0f, 0f, 0f, 0f)
    private var accumulation: TextureTarget? = null
    private var vertices: StagedVertexBuffer? = null
    private val composites = mutableMapOf<SceneGpuFormat, SceneRenderPipeline>()

    private val accumulate = SceneRenderPipeline.builder()
        .withLocation(id("oit_accumulate"))
        .withVertexShader("core/particle")
        .withFragmentShader(id("core/oit_accumulate"))
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
        .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
        .withPrimitiveTopology(SceneTopology.QUADS)
        .withColorTargetState(SceneColorTargetState(Optional.of(SceneBlendFunction.ADDITIVE), format, SceneColorTargetState.WRITE_ALL))
        .withDepthStencilState(SceneDepthStencilState(SceneCompareOp.GREATER_THAN_OR_EQUAL, false))
        .withCull(false)
        .build()

    private fun composite(format: SceneGpuFormat): SceneRenderPipeline = composites.getOrPut(format) {
        SceneRenderPipeline.builder()
            .withLocation(id("oit_composite"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(id("core/oit_composite"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withPrimitiveTopology(SceneTopology.TRIANGLES)
            .withColorTargetState(SceneColorTargetState(Optional.of(SceneBlendFunction.TRANSLUCENT), format, SceneColorTargetState.WRITE_COLOR))
            .withCull(false)
            .build()
    }

    fun beginFrame(): OrientedQuadRenderState? {
        quads.clear()
        return quads.takeIf {
            ParticleDrawingConfig.CLIENT.weightedParticleTransparency.get() && useWeightedParticleTransparency()
        }
    }

    fun render() {
        if (quads.isEmpty) return
        val minecraft = Minecraft.getInstance()
        val target = minecraft.gameRenderer.mainRenderTarget()
        val color = requireNotNull(target.colorTextureView)
        val depth = requireNotNull(target.depthTextureView)
        val current = accumulation
        val buffer = if (current?.width == target.width && current.height == target.height) current else {
            current?.destroyBuffers()
            createOitTexture(target.width, target.height, format).also { accumulation = it }
        }
        val accum = requireNotNull(buffer.colorTextureView)
        val staged = vertices ?: StagedVertexBuffer({ "Particle OIT vertices" }, 16384).also { vertices = it }
        val draws = quads.layers().associateWith { layer ->
            staged.appendDraw(DefaultVertexFormat.PARTICLE, SceneTopology.QUADS).also { draw ->
                quads.buildLayer(layer, staged.getVertexBuilder(draw))
            }
        }
        staged.upload()
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy())
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(SceneFilterMode.NEAREST)
        encoder.createRenderPass({ "Accumulate translucent particles" }, accum, Optional.of(clear), depth, OptionalDouble.empty()).use { pass ->
            pass.useScenePipeline(accumulate)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.bindSceneTexture("Sampler2", minecraft.gameRenderer.lightmap(), sampler)
            for ((layer, draw) in draws) {
                val info = staged.getExecuteInfo(draw) ?: continue
                val texture = minecraft.textureManager.getTexture(layer.textureAtlasLocation())
                pass.bindSceneTexture("Sampler0", texture.textureView, texture.sampler)
                pass.setVertexBuffer(0, info.vertexBuffer().slice())
                pass.setIndexBuffer(info.indexBuffer(), info.indexType())
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0)
            }
        }
        encoder.createRenderPass({ "Composite translucent particles" }, color, Optional.empty()).use { pass ->
            pass.useScenePipeline(composite(color.texture().format))
            pass.bindSceneTexture("InSampler", accum, sampler)
            pass.draw(3, 1, 0, 0)
        }
    }

    fun endFrame() {
        quads.clear()
        vertices?.endFrame()
    }

    fun close() {
        endFrame()
        vertices?.close()
        vertices = null
        accumulation?.destroyBuffers()
        accumulation = null
    }

    private fun id(path: String) = Identifier.fromNamespaceAndPath(ParticleDrawing.MODID, path)
}
