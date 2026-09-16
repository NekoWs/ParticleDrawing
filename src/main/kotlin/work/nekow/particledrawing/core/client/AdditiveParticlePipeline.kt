package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.resources.Identifier

// 加法混合粒子管线（编辑器效果层的 blend=additive，容器 flags bit7）。
//
// 配方与 RenderPipelines.PARTICLE_SNIPPET + TRANSLUCENT_PARTICLE 一致（对照 26.2 字节码）：
// GLOBALS / MATRICES_PROJECTION / FOG / SAMPLER0_SAMPLER2 四个 bind group layout、
// core/particle 双着色器、DefaultVertexFormat.PARTICLE、QUADS、默认深度模板、关剔除，
// 只把 ColorTargetState 的混合函数换成加法。
//
// **混合函数为什么是 LIGHTNING 而不是 ADDITIVE（重要，勿改）**：
// 26.2 的 BlendFunction.ADDITIVE = (ONE, ONE)，是**预乘 alpha 的加法**——它假设源色已被
// alpha 预乘。而 core/particle.fsh 输出的是**直通 alpha**（texture * vertexColor，无预乘），
// 用 (ONE, ONE) 会让 alpha=0.3 的粒子以满亮度相加，与编辑器预览（three.js AdditiveBlending
// = SRC_ALPHA, ONE）**不一致**——预览骗人。BlendFunction.LIGHTNING = (SRC_ALPHA, ONE)，
// 直通 alpha 加法，与编辑器预览逐像素一致。
// 不新建着色器：粒子着色器本身与混合无关，混合只由管线的 blend 状态决定。
val ADDITIVE_PARTICLE: RenderPipeline = RenderPipeline.builder()
    .withBindGroupLayout(BindGroupLayouts.GLOBALS)
    .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
    .withBindGroupLayout(BindGroupLayouts.FOG)
    .withVertexShader("core/particle")
    .withFragmentShader("core/particle")
    .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
    .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
    .withPrimitiveTopology(PrimitiveTopology.QUADS)
    .withDepthStencilState(DepthStencilState.DEFAULT)
    .withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
    .withCull(false)
    .withLocation(Identifier.fromNamespaceAndPath("particledrawing", "additive_particle"))
    .build()