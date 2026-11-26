package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryStack
import work.nekow.particledrawing.platform.PlatformCompat
import java.util.Optional
import java.util.OptionalDouble

internal object ParticleInstancePass {
    private val shader = Identifier.fromNamespaceAndPath("particledrawing", "core/particle_instances")
    private fun pipeline(name: String, blend: SceneBlendFunction, topology: SceneTopology, writeDepth: Boolean) =
        SceneRenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("particledrawing", name))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(particleInstanceLayout())
            .withVertexBinding(0, SceneVertexFormat.builder(0)
                .addAttribute("Corner", SceneGpuFormat.RGB32_FLOAT)
                .addAttribute("FaceNormal", SceneGpuFormat.RGB32_FLOAT)
                .addAttribute("ModelUV", SceneGpuFormat.RG32_FLOAT).build())
            .withPrimitiveTopology(topology)
            .withDepthStencilState(SceneDepthStencilState(SceneCompareOp.GREATER_THAN_OR_EQUAL, writeDepth))
            .withColorTargetState(SceneColorTargetState(blend))
            .withCull(false)
            .build()
    private data class PipelineKey(val blend: ParticleInstanceDraws.Blend, val mesh: Boolean)
    private val pipelines = hashMapOf<PipelineKey, SceneRenderPipeline>()

    private fun pipeline(material: ParticleInstanceDraws.Material): SceneRenderPipeline {
        val key = PipelineKey(material.blend, material.model != null)
        return pipelines.getOrPut(key) {
            val additive = key.blend == ParticleInstanceDraws.Blend.ADDITIVE
            val topology = if (key.mesh) SceneTopology.TRIANGLES else SceneTopology.TRIANGLE_STRIP
            val shape = if (key.mesh) "mesh" else "quad"
            pipeline("particle_instances_${key.blend.name.lowercase()}_$shape",
                if (additive) SceneBlendFunction.LIGHTNING else SceneBlendFunction.TRANSLUCENT,
                topology, writeDepth = key.blend == ParticleInstanceDraws.Blend.OPAQUE)
        }
    }
    private class Arena {
        val buffers = arrayOfNulls<SceneGpuBuffer>(3)
        var cursor = buffers.lastIndex
        var revision = Long.MIN_VALUE

        fun close() { for (buffer in buffers) buffer?.close() }
    }
    private val resident = hashMapOf<ParticleInstanceDraws.Block, SceneGpuBuffer>()
    private val used = hashSetOf<ParticleInstanceDraws.Block>()
    private val arenas = hashMapOf<ParticleInstanceDraws.CpuPages, Arena>()
    private val usedArenas = hashSetOf<ParticleInstanceDraws.CpuPages>()
    private val geometry = hashMapOf<ParticleModelRenderer.Model?, SceneGpuBuffer>()
    private val usedGeometry = hashSetOf<ParticleModelRenderer.Model?>()
    private data class Draw(val material: ParticleInstanceDraws.Material, val vertices: SceneGpuBufferSlice,
                            val frame: SceneGpuBufferSlice, val data: SceneGpuBufferSlice, val count: Int)

    fun render() {
        // 调试旧管线时，桥接粒子由普通绘制提交。
        if (ShaderCompat.legacyPipelines()) return
        if (!ParticleInstanceDraws.hasDraws()) return
        val mc = Minecraft.getInstance()
        val target = mc.gameRenderer.mainRenderTarget()
        val device = RenderSystem.getDevice()
        val encoder = device.createCommandEncoder()
        val memory = encoder.transientMemory()
        val align = device.deviceInfo.limits().minUniformOffsetAlignment().toLong()
        val blockSize = ParticleInstanceDraws.BLOCK_BYTES.toLong()
        val stride = ((blockSize + align - 1) / align * align).toInt()
        val draws = arrayListOf<Draw>()
        val view = RenderSystem.getModelViewMatrixCopy()
        MemoryStack.stackPush().use { stack ->
            val frame = stack.calloc(ParticleInstanceDraws.FRAME_BYTES)
            val unorderedFrames = hashMapOf<ParticleInstanceDraws.Frame, SceneGpuBufferSlice>()
            ParticleInstanceDraws.forEach(PlatformCompat.cameraPosition(mc), view) { material, block, uniforms, order, count ->
                fun uploadFrame(): SceneGpuBufferSlice {
                    frame.clear()
                    uniforms.write(frame, order, count)
                    return memory.uploadGpu(frame, align, SceneGpuBuffer.USAGE_UNIFORM)
                }
                val frameSlice = if (order == null) unorderedFrames.getOrPut(uniforms, ::uploadFrame) else uploadFrame()
                val pages = block.pageSet
                val data = if (pages == null) {
                    used += block
                    resident.getOrPut(block) {
                        device.createBuffer({ "GPU particle seeds" }, SceneGpuBuffer.USAGE_UNIFORM, block.bytes())
                    }.slice()
                } else {
                    usedArenas += pages
                    val arena = arenas.getOrPut(pages, ::Arena)
                    if (arena.revision != pages.revision) {
                        arena.cursor = (arena.cursor + 1) % arena.buffers.size
                        val input = pages.bytes(stride)
                        val bytes = input.remaining().toLong()
                        val buffer = arena.buffers[arena.cursor]
                        if (buffer == null || buffer.size() < bytes) {
                            buffer?.close()
                            arena.buffers[arena.cursor] = device.createBuffer({ "CPU particle pages" },
                                SceneGpuBuffer.USAGE_UNIFORM or SceneGpuBuffer.USAGE_COPY_DST, input)
                        } else encoder.writeToBuffer(buffer.slice(0L, bytes), input)
                        arena.revision = pages.revision
                    }
                    checkNotNull(arena.buffers[arena.cursor]).slice(block.pageIndex.toLong() * stride, blockSize)
                }
                val model = material.model
                usedGeometry += model
                val mesh = geometry.getOrPut(model) {
                    MemoryStack.stackPush().use { meshStack ->
                        val data = meshStack.malloc(ParticleInstanceGeometry.count(model) * ParticleInstanceGeometry.STRIDE)
                        ParticleInstanceGeometry.write(model, data)
                        device.createBuffer({ "Particle instance geometry" }, SceneGpuBuffer.USAGE_VERTEX, data)
                    }
                }
                draws += Draw(material, mesh.slice(), frameSlice, data, count)
            }
        }
        if (draws.isEmpty()) return
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(view)
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(SceneFilterMode.LINEAR)
        encoder.createRenderPass({ "Particle instances" }, requireNotNull(target.colorTextureView), Optional.empty(),
            requireNotNull(target.depthTextureView), OptionalDouble.empty()).use { pass ->
            var currentPipeline: SceneRenderPipeline? = null
            var textureId: Identifier? = null
            var vertices: SceneGpuBufferSlice? = null
            var uniforms: SceneGpuBufferSlice? = null
            for (draw in draws) {
                val pipeline = pipeline(draw.material)
                if (currentPipeline !== pipeline) {
                    currentPipeline = pipeline
                    pass.useScenePipeline(pipeline)
                    RenderSystem.bindDefaultUniforms(pass)
                    pass.setUniform("DynamicTransforms", transforms)
                    pass.bindSceneTexture("Sampler2", mc.gameRenderer.lightmap(), sampler)
                    textureId = null
                    vertices = null
                    uniforms = null
                }
                if (textureId != draw.material.texture) {
                    textureId = draw.material.texture
                    val texture = mc.textureManager.getTexture(draw.material.texture)
                    pass.bindSceneTexture("Sampler0", texture.textureView, texture.sampler)
                }
                if (vertices != draw.vertices) {
                    vertices = draw.vertices
                    pass.setVertexBuffer(0, draw.vertices)
                }
                if (uniforms != draw.frame) {
                    uniforms = draw.frame
                    pass.setUniform("PDFrame", draw.frame)
                }
                pass.setUniform("PDInstances", draw.data)
                pass.draw(ParticleInstanceGeometry.count(draw.material.model), draw.count, 0, 0)
            }
        }
    }

    fun endFrame() {
        ParticleInstanceDraws.endFrame()
        resident.entries.removeIf { (block, buffer) ->
            if (block in used) false else { buffer.close(); true }
        }
        used.clear()
        arenas.entries.removeIf { (pages, arena) ->
            if (pages in usedArenas) false else { arena.close(); true }
        }
        usedArenas.clear()
        geometry.entries.removeIf { (model, buffer) ->
            if (model in usedGeometry) false else { buffer.close(); true }
        }
        usedGeometry.clear()
    }

    fun close() {
        ParticleInstanceDraws.close()
        for (buffer in resident.values) buffer.close()
        resident.clear()
        used.clear()
        for (arena in arenas.values) arena.close()
        arenas.clear()
        usedArenas.clear()
        for (buffer in geometry.values) buffer.close()
        geometry.clear()
        usedGeometry.clear()
    }
}

