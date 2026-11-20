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
    private val pipeline by lazy {
        SceneRenderPipeline.builder()
            .withLocation(shader)
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .sceneDepthConvention()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(sceneProjectionLayout)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(particleInstanceLayout())
            .withVertexBinding(0, SceneVertexFormat.builder(0).addAttribute("Corner", SceneGpuFormat.RGB32_FLOAT).build())
            .withPrimitiveTopology(SceneTopology.TRIANGLE_STRIP)
            .withDepthStencilState(SceneDepthStencilState(SceneCompareOp.GREATER_THAN_OR_EQUAL, false))
            .withColorTargetState(SceneColorTargetState(SceneBlendFunction.LIGHTNING))
            .withCull(false)
            .build()
    }
    private var quad: SceneGpuBuffer? = null
    private val resident = hashMapOf<ParticleInstanceDraws.Block, SceneGpuBuffer>()
    private val used = hashSetOf<ParticleInstanceDraws.Block>()
    private data class Draw(val texture: Identifier, val frame: SceneGpuBufferSlice, val data: SceneGpuBufferSlice, val count: Int)

    fun render() {
        if (!ParticleInstanceDraws.hasDraws()) return
        val mc = Minecraft.getInstance()
        val target = mc.gameRenderer.mainRenderTarget()
        val device = RenderSystem.getDevice()
        val encoder = device.createCommandEncoder()
        val memory = encoder.transientMemory()
        val align = device.deviceInfo.limits().minUniformOffsetAlignment().toLong()
        val draws = arrayListOf<Draw>()
        val base = quad ?: MemoryStack.stackPush().use { stack ->
            val corners = stack.malloc(4 * 3 * Float.SIZE_BYTES)
            for (y in listOf(-1f, 1f)) for (x in listOf(-1f, 1f)) corners.putFloat(x).putFloat(y).putFloat(0f)
            device.createBuffer({ "Particle instance quad" }, SceneGpuBuffer.USAGE_VERTEX, corners.flip())
        }.also { quad = it }
        MemoryStack.stackPush().use { stack ->
            val frame = stack.malloc(ParticleInstanceDraws.FRAME_BYTES)
            ParticleInstanceDraws.forEach(PlatformCompat.cameraPosition(mc)) { texture, blocks, uniforms ->
                frame.clear()
                uniforms.write(frame)
                val frameSlice = memory.uploadGpu(frame, align, SceneGpuBuffer.USAGE_UNIFORM)
                for (block in blocks) {
                    val data = if (block.persistent) {
                        used += block
                        resident.getOrPut(block) {
                            device.createBuffer({ "GPU particle seeds" }, SceneGpuBuffer.USAGE_UNIFORM, block.bytes())
                        }.slice()
                    } else memory.uploadGpu(block.bytes(), align, SceneGpuBuffer.USAGE_UNIFORM)
                    draws += Draw(texture, frameSlice, data, block.count)
                }
            }
        }
        if (draws.isEmpty()) return
        val transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy())
        encoder.createRenderPass({ "Particle instances" }, requireNotNull(target.colorTextureView), Optional.empty(),
            requireNotNull(target.depthTextureView), OptionalDouble.empty()).use { pass ->
            pass.useScenePipeline(pipeline)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setVertexBuffer(0, base.slice())
            for (draw in draws) {
                val texture = mc.textureManager.getTexture(draw.texture)
                pass.bindSceneTexture("Sampler0", texture.textureView, texture.sampler)
                pass.setUniform("PDFrame", draw.frame)
                pass.setUniform("PDInstances", draw.data)
                pass.draw(4, draw.count, 0, 0)
            }
        }
    }

    fun endFrame() {
        ParticleInstanceDraws.endFrame()
        resident.entries.removeIf { (block, buffer) ->
            if (block in used) false else { buffer.close(); true }
        }
        used.clear()
    }

    fun close() {
        ParticleInstanceDraws.close()
        for (buffer in resident.values) buffer.close()
        resident.clear()
        used.clear()
        quad?.close()
        quad = null
    }
}
