package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterShadersEvent
import org.lwjgl.opengl.GL33C.*
import org.lwjgl.system.MemoryStack
import work.nekow.particledrawing.ParticleDrawing

@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
internal object ParticleInstancePass {
    private val format = VertexFormat.builder().add("Corner", VertexFormatElement.POSITION)
        .add("FaceNormal", VertexFormatElement.NORMAL).add("ModelUV", VertexFormatElement.UV0).build()
    private var shader: ShaderInstance? = null
    private var frame = 0
    private data class Geometry(val vao: Int, val buffer: Int)
    private data class Arena(val buffer: Int, var revision: Long = Long.MIN_VALUE)
    private val resident = hashMapOf<ParticleInstanceDraws.Block, Int>()
    private val used = hashSetOf<ParticleInstanceDraws.Block>()
    private val arenas = hashMapOf<ParticleInstanceDraws.CpuPages, Arena>()
    private val usedArenas = hashSetOf<ParticleInstanceDraws.CpuPages>()
    private val geometry = hashMapOf<ParticleModelRenderer.Model?, Geometry>()
    private val usedGeometry = hashSetOf<ParticleModelRenderer.Model?>()

    @SubscribeEvent
    @JvmStatic fun register(event: RegisterShadersEvent) {
        event.registerShader(ShaderInstance(event.resourceProvider, Identifier.fromNamespaceAndPath(
            ParticleDrawing.MODID, "particle_instances"), format)) { active ->
            shader = active
            glUniformBlockBinding(active.id, glGetUniformBlockIndex(active.id, "PDInstances"), 0)
            glUniformBlockBinding(active.id, glGetUniformBlockIndex(active.id, "PDFrame"), 1)
        }
    }

    fun render(camera: Vec3, lightmap: LightTexture) {
        try {
            val active = shader ?: return
            if (!ParticleInstanceDraws.hasDraws()) return
            renderInstances(active, camera, lightmap)
        } finally {
            ParticleInstanceDraws.endFrame()
            resident.entries.removeIf { (block, buffer) ->
                if (block in used) false else { glDeleteBuffers(buffer); true }
            }
            used.clear()
            arenas.entries.removeIf { (pages, arena) ->
                if (pages in usedArenas) false else { glDeleteBuffers(arena.buffer); true }
            }
            usedArenas.clear()
            geometry.entries.removeIf { (model, mesh) ->
                if (model in usedGeometry) false else { release(mesh); true }
            }
            usedGeometry.clear()
        }
    }

    private fun renderInstances(active: ShaderInstance, camera: Vec3, lightmap: LightTexture) {
        val previousProgram = glGetInteger(GL_CURRENT_PROGRAM)
        val previousTexture = RenderSystem.getShaderTexture(0)
        val previousLightmap = RenderSystem.getShaderTexture(2)
        val previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING)
        val previousArray = glGetInteger(GL_ARRAY_BUFFER_BINDING)
        val previousUniform = glGetInteger(GL_UNIFORM_BUFFER_BINDING)
        val previousInstances = UniformBinding(0)
        val previousFrame = UniformBinding(1)
        val alignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT)
        val blockSize = ParticleInstanceDraws.BLOCK_BYTES
        val stride = (blockSize + alignment - 1) / alignment * alignment
        val depth = glIsEnabled(GL_DEPTH_TEST)
        val writeDepth = glGetBoolean(GL_DEPTH_WRITEMASK)
        val blend = glIsEnabled(GL_BLEND)
        val cull = glIsEnabled(GL_CULL_FACE)
        val src = glGetInteger(GL_BLEND_SRC_RGB)
        val dst = glGetInteger(GL_BLEND_DST_RGB)
        val srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA)
        val dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA)
        try {
            if (frame == 0) frame = glGenBuffers()
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(false)
            RenderSystem.disableCull()
            RenderSystem.enableBlend()
            lightmap.turnOnLightLayer()
            val mc = Minecraft.getInstance()
            val view = RenderSystem.getModelViewMatrix()
            glBindBufferBase(GL_UNIFORM_BUFFER, 1, frame)
            MemoryStack.stackPush().use { stack ->
                val uniforms = stack.calloc(ParticleInstanceDraws.FRAME_BYTES)
                var texture: Identifier? = null
                var blendMode: ParticleInstanceDraws.Blend? = null
                var previousFrameData: ParticleInstanceDraws.Frame? = null
                ParticleInstanceDraws.forEach(camera, view) { material, block, state, order, count ->
                    if (texture != material.texture) {
                        texture = material.texture
                        RenderSystem.setShaderTexture(0, material.texture)
                        active.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, view, RenderSystem.getProjectionMatrix(), mc.window)
                        active.apply()
                    }
                    if (blendMode != material.blend) {
                        blendMode = material.blend
                        RenderSystem.depthMask(material.blend == ParticleInstanceDraws.Blend.OPAQUE)
                        if (material.blend == ParticleInstanceDraws.Blend.ADDITIVE) GlStateManager._blendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ONE)
                        else RenderSystem.defaultBlendFunc()
                    }
                    if (order != null || state != previousFrameData) {
                        uniforms.clear()
                        state.write(uniforms, order, count)
                        glBindBuffer(GL_UNIFORM_BUFFER, frame)
                        glBufferData(GL_UNIFORM_BUFFER, uniforms, GL_STREAM_DRAW)
                        previousFrameData = if (order == null) state else null
                    }
                    val model = material.model
                    usedGeometry += model
                    val mesh = geometry.getOrPut(model) { createGeometry(model) }
                    GlStateManager._glBindVertexArray(mesh.vao)
                    val pages = block.pageSet
                    if (pages == null) {
                        used += block
                        val buffer = resident.getOrPut(block) {
                            glGenBuffers().also {
                                glBindBuffer(GL_UNIFORM_BUFFER, it)
                                glBufferData(GL_UNIFORM_BUFFER, block.bytes(), GL_STATIC_DRAW)
                            }
                        }
                        glBindBufferBase(GL_UNIFORM_BUFFER, 0, buffer)
                    } else {
                        usedArenas += pages
                        val arena = arenas.getOrPut(pages) { Arena(glGenBuffers()) }
                        if (arena.revision != pages.revision) {
                            glBindBuffer(GL_UNIFORM_BUFFER, arena.buffer)
                            glBufferData(GL_UNIFORM_BUFFER, pages.bytes(stride), GL_STREAM_DRAW)
                            arena.revision = pages.revision
                        }
                        glBindBufferRange(GL_UNIFORM_BUFFER, 0, arena.buffer, block.pageIndex.toLong() * stride, blockSize.toLong())
                    }
                    val topology = if (model == null) GL_TRIANGLE_STRIP else GL_TRIANGLES
                    glDrawArraysInstanced(topology, 0, ParticleInstanceGeometry.count(model), count)
                }
            }
        } finally {
            active.clear()
            GlStateManager._glUseProgram(previousProgram)
            RenderSystem.setShaderTexture(0, previousTexture)
            RenderSystem.setShaderTexture(2, previousLightmap)
            GlStateManager._glBindVertexArray(previousVao)
            GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, previousArray)
            previousInstances.restore()
            previousFrame.restore()
            glBindBuffer(GL_UNIFORM_BUFFER, previousUniform)
            GlStateManager._blendFuncSeparate(src, dst, srcAlpha, dstAlpha)
            RenderSystem.depthMask(writeDepth)
            if (!depth) RenderSystem.disableDepthTest()
            if (!blend) RenderSystem.disableBlend()
            if (cull) RenderSystem.enableCull()
        }
    }

    private class UniformBinding(private val index: Int) {
        private val buffer = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, index)
        private val offset = glGetInteger64i(GL_UNIFORM_BUFFER_START, index)
        private val size = glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, index)

        fun restore() {
            if (buffer != 0 && size > 0) glBindBufferRange(GL_UNIFORM_BUFFER, index, buffer, offset, size)
            else glBindBufferBase(GL_UNIFORM_BUFFER, index, buffer)
        }
    }

    private fun createGeometry(model: ParticleModelRenderer.Model?): Geometry {
        val mesh = Geometry(glGenVertexArrays(), glGenBuffers())
        GlStateManager._glBindVertexArray(mesh.vao)
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, mesh.buffer)
        val stride = ParticleInstanceGeometry.STRIDE
        MemoryStack.stackPush().use { stack ->
            val data = stack.malloc(ParticleInstanceGeometry.count(model) * stride)
            ParticleInstanceGeometry.write(model, data)
            glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW)
        }
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 12L)
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 24L)
        for (attribute in 0..2) glEnableVertexAttribArray(attribute)
        return mesh
    }

    private fun release(mesh: Geometry) {
        glDeleteVertexArrays(mesh.vao)
        glDeleteBuffers(mesh.buffer)
    }

    fun close() {
        ParticleInstanceDraws.close()
        for (buffer in resident.values) glDeleteBuffers(buffer)
        resident.clear()
        used.clear()
        for (arena in arenas.values) glDeleteBuffers(arena.buffer)
        arenas.clear()
        usedArenas.clear()
        for (mesh in geometry.values) release(mesh)
        geometry.clear()
        usedGeometry.clear()
        if (frame != 0) glDeleteBuffers(frame)
        frame = 0
    }
}
