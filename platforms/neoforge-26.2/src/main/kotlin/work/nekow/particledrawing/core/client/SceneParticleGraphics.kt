package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.BindGroupLayouts

internal typealias SceneGpuFormat = com.mojang.blaze3d.GpuFormat
internal typealias SceneBindGroupLayout = com.mojang.blaze3d.pipeline.BindGroupLayout
internal typealias SceneBlendFunction = com.mojang.blaze3d.pipeline.BlendFunction
internal typealias SceneColorTargetState = com.mojang.blaze3d.pipeline.ColorTargetState
internal typealias SceneCompareOp = com.mojang.blaze3d.platform.CompareOp
internal typealias SceneDepthStencilState = com.mojang.blaze3d.pipeline.DepthStencilState
internal typealias SceneTopology = com.mojang.blaze3d.PrimitiveTopology
internal typealias SceneRenderPipeline = com.mojang.blaze3d.pipeline.RenderPipeline
internal typealias SceneFilterMode = com.mojang.blaze3d.textures.FilterMode
internal typealias SceneVertexFormat = com.mojang.blaze3d.vertex.VertexFormat
internal typealias SceneRenderPass = com.mojang.blaze3d.systems.RenderPass
internal typealias SceneTextureView = com.mojang.blaze3d.textures.GpuTextureView
internal typealias SceneSampler = com.mojang.blaze3d.textures.GpuSampler

internal val sceneProjectionLayout get() = BindGroupLayouts.MATRICES_PROJECTION

internal fun sceneSamplerLayout(): SceneBindGroupLayout =
    SceneBindGroupLayout.builder().withSampler("SceneDepth").withSampler("SceneColor").build()

internal fun com.mojang.blaze3d.pipeline.RenderPipeline.Builder.sceneDepthConvention() =
    withShaderDefine("PD_ZERO_TO_ONE", if (RenderSystem.getDevice().deviceInfo.isZZeroToOne) 1 else 0)

internal fun SceneRenderPass.useScenePipeline(pipeline: SceneRenderPipeline) = setPipeline(pipeline)

internal fun SceneRenderPass.bindSceneTexture(name: String, texture: SceneTextureView, sampler: SceneSampler) =
    bindTexture(name, texture, sampler)
