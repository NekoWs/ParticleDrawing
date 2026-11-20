package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import net.minecraft.client.Minecraft
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
    private val format = VertexFormat.builder().add("Corner", VertexFormatElement.POSITION).build()
    private var shader: ShaderInstance? = null
    private var vao = 0
    private var quad = 0
    private var dynamic = 0
    private var frame = 0
    private val resident = hashMapOf<ParticleInstanceDraws.Block, Int>()
    private val used = hashSetOf<ParticleInstanceDraws.Block>()

    @SubscribeEvent
    @JvmStatic fun register(event: RegisterShadersEvent) {
        event.registerShader(ShaderInstance(event.resourceProvider, Identifier.fromNamespaceAndPath(
            ParticleDrawing.MODID, "particle_instances"), format)) { active ->
            shader = active
            glUniformBlockBinding(active.id, glGetUniformBlockIndex(active.id, "PDInstances"), 0)
            glUniformBlockBinding(active.id, glGetUniformBlockIndex(active.id, "PDFrame"), 1)
        }
    }

    fun render(camera: Vec3) {
        try {
            val active = shader ?: return
            if (!ParticleInstanceDraws.hasDraws()) return
            renderInstances(active, camera)
        } finally {
            ParticleInstanceDraws.endFrame()
            resident.entries.removeIf { (block, buffer) ->
                if (block in used) false else { glDeleteBuffers(buffer); true }
            }
            used.clear()
        }
    }

    private fun renderInstances(active: ShaderInstance, camera: Vec3) {
        val previousProgram = glGetInteger(GL_CURRENT_PROGRAM)
        val previousTexture = RenderSystem.getShaderTexture(0)
        val previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING)
        val previousArray = glGetInteger(GL_ARRAY_BUFFER_BINDING)
        val previousUniform = glGetInteger(GL_UNIFORM_BUFFER_BINDING)
        val previousInstances = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 0)
        val previousFrame = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 1)
        val depth = glIsEnabled(GL_DEPTH_TEST)
        val writeDepth = glGetBoolean(GL_DEPTH_WRITEMASK)
        val blend = glIsEnabled(GL_BLEND)
        val cull = glIsEnabled(GL_CULL_FACE)
        val src = glGetInteger(GL_BLEND_SRC_RGB)
        val dst = glGetInteger(GL_BLEND_DST_RGB)
        val srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA)
        val dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA)
        try {
            setupQuad()
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(false)
            RenderSystem.disableCull()
            RenderSystem.enableBlend()
            GlStateManager._blendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ONE)
            val mc = Minecraft.getInstance()
            glBindBufferBase(GL_UNIFORM_BUFFER, 1, frame)
            MemoryStack.stackPush().use { stack ->
                val uniforms = stack.malloc(ParticleInstanceDraws.FRAME_BYTES)
                ParticleInstanceDraws.forEach(camera) { texture, blocks, state ->
                    RenderSystem.setShaderTexture(0, texture)
                    active.setDefaultUniforms(VertexFormat.Mode.TRIANGLE_STRIP, RenderSystem.getModelViewMatrix(),
                        RenderSystem.getProjectionMatrix(), mc.window)
                    active.apply()
                    uniforms.clear()
                    state.write(uniforms)
                    glBindBuffer(GL_UNIFORM_BUFFER, frame)
                    glBufferData(GL_UNIFORM_BUFFER, uniforms, GL_STREAM_DRAW)
                    for (block in blocks) {
                        val buffer = if (block.persistent) {
                            used += block
                            resident.getOrPut(block) {
                                glGenBuffers().also {
                                    glBindBuffer(GL_UNIFORM_BUFFER, it)
                                    glBufferData(GL_UNIFORM_BUFFER, block.bytes(), GL_STATIC_DRAW)
                                }
                            }
                        } else dynamic.also {
                            glBindBuffer(GL_UNIFORM_BUFFER, it)
                            glBufferData(GL_UNIFORM_BUFFER, block.bytes(), GL_STREAM_DRAW)
                        }
                        glBindBufferBase(GL_UNIFORM_BUFFER, 0, buffer)
                        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, block.count)
                    }
                }
            }
        } finally {
            active.clear()
            GlStateManager._glUseProgram(previousProgram)
            RenderSystem.setShaderTexture(0, previousTexture)
            GlStateManager._glBindVertexArray(previousVao)
            GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, previousArray)
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, previousInstances)
            glBindBufferBase(GL_UNIFORM_BUFFER, 1, previousFrame)
            glBindBuffer(GL_UNIFORM_BUFFER, previousUniform)
            GlStateManager._blendFuncSeparate(src, dst, srcAlpha, dstAlpha)
            RenderSystem.depthMask(writeDepth)
            if (!depth) RenderSystem.disableDepthTest()
            if (!blend) RenderSystem.disableBlend()
            if (cull) RenderSystem.enableCull()
        }
    }

    private fun setupQuad() {
        if (vao != 0) {
            GlStateManager._glBindVertexArray(vao)
            return
        }
        vao = glGenVertexArrays()
        quad = glGenBuffers()
        dynamic = glGenBuffers()
        frame = glGenBuffers()
        GlStateManager._glBindVertexArray(vao)
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, quad)
        glBufferData(GL_ARRAY_BUFFER, floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f), GL_STATIC_DRAW)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L)
        glEnableVertexAttribArray(0)
    }

    fun close() {
        ParticleInstanceDraws.close()
        for (buffer in resident.values) glDeleteBuffers(buffer)
        resident.clear()
        used.clear()
        if (vao == 0) return
        glDeleteVertexArrays(vao)
        glDeleteBuffers(intArrayOf(quad, dynamic, frame))
        vao = 0
    }
}
