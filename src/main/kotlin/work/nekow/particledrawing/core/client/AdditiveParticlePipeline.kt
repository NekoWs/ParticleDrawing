package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.resources.Identifier

// 加法混合粒子管线：编辑器效果层 blend=additive（容器 flags bit7）走这条管线。
// 其余配方沿用原版粒子管线，只把 ColorTargetState 的混合函数换成加法。
//
// 混合函数必须是 BlendFunction.LIGHTNING = (SRC_ALPHA, ONE)：core/particle.fsh 输出直通 alpha
// （texture * vertexColor，无预乘），而 ADDITIVE = (ONE, ONE) 是预乘 alpha 的加法，会让 alpha=0.3
// 的粒子以满亮度相加。LIGHTNING 与编辑器预览的加法混合逐像素一致，不要改回 ADDITIVE。
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