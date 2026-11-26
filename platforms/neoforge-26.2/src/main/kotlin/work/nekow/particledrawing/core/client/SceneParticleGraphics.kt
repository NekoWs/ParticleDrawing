package work.nekow.particledrawing.core.client


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
internal typealias SceneGpuBuffer = com.mojang.blaze3d.buffers.GpuBuffer
internal typealias SceneGpuBufferSlice = com.mojang.blaze3d.buffers.GpuBufferSlice

internal fun sceneSamplerLayout(): SceneBindGroupLayout =
    SceneBindGroupLayout.builder().withSampler("SceneDepth").withSampler("SceneColor").build()

internal fun particleInstanceLayout(): SceneBindGroupLayout = SceneBindGroupLayout.builder()
    .withSampler("Sampler0")
    .withSampler("Sampler2")
    .withUniform("PDInstances", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
    .withUniform("PDFrame", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
    .build()

internal fun SceneRenderPass.useScenePipeline(pipeline: SceneRenderPipeline) = setPipeline(pipeline)

internal fun SceneRenderPass.bindSceneTexture(name: String, texture: SceneTextureView, sampler: SceneSampler) =
    bindTexture(name, texture, sampler)

internal fun useWeightedParticleTransparency(): Boolean = !ShaderCompat.legacyPipelines()

internal fun createOitTexture(width: Int, height: Int, format: SceneGpuFormat) =
    com.mojang.blaze3d.pipeline.TextureTarget("Particle OIT", width, height, false, format)
