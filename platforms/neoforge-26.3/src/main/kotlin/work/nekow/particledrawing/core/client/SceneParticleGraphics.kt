package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.systems.RenderSystem

internal typealias SceneGpuFormat = com.mojang.renderpearl.api.GpuFormat
internal typealias SceneBindGroupLayout = com.mojang.renderpearl.api.pipeline.BindGroupLayout
internal typealias SceneBlendFunction = com.mojang.renderpearl.api.pipeline.BlendFunction
internal typealias SceneColorTargetState = com.mojang.renderpearl.api.pipeline.ColorTargetState
internal typealias SceneCompareOp = com.mojang.renderpearl.api.pipeline.CompareOp
internal typealias SceneDepthStencilState = com.mojang.renderpearl.api.pipeline.DepthStencilState
internal typealias SceneTopology = com.mojang.renderpearl.api.pipeline.PrimitiveTopology
internal typealias SceneRenderPipeline = com.mojang.renderpearl.api.pipeline.RenderPipeline
internal typealias SceneFilterMode = com.mojang.renderpearl.api.textures.FilterMode
internal typealias SceneVertexFormat = com.mojang.renderpearl.api.vertex.VertexFormat
internal typealias SceneRenderPass = com.mojang.renderpearl.api.commands.RenderPass
internal typealias SceneTextureView = com.mojang.renderpearl.api.textures.GpuTextureView
internal typealias SceneSampler = com.mojang.renderpearl.api.textures.GpuSampler
internal typealias SceneGpuBuffer = com.mojang.renderpearl.api.buffers.GpuBuffer
internal typealias SceneGpuBufferSlice = com.mojang.renderpearl.api.buffers.GpuBufferSlice

internal fun sceneSamplerLayout(): SceneBindGroupLayout = SceneBindGroupLayout.builder()
    .withUniform("SceneDepth", com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER)
    .withUniform("SceneColor", com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER)
    .build()

internal fun particleInstanceLayout(): SceneBindGroupLayout = SceneBindGroupLayout.builder()
    .withUniform("Sampler0", com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER)
    .withUniform("Sampler2", com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER)
    .withUniform("PDInstances", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
    .withUniform("PDFrame", com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
    .build()

internal fun SceneRenderPass.useScenePipeline(pipeline: SceneRenderPipeline) =
    setPipeline(RenderSystem.getCompiledPipeline(pipeline))

internal fun SceneRenderPass.bindSceneTexture(name: String, texture: SceneTextureView, sampler: SceneSampler) =
    setUniform(name, texture, sampler)

internal fun useWeightedParticleTransparency(): Boolean = ShaderCompat.postShaderParticles()

internal fun createOitTexture(width: Int, height: Int, format: SceneGpuFormat) =
    com.mojang.blaze3d.pipeline.TextureTarget("Particle OIT", width, height, format, null)
