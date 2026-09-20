package work.nekow.particledrawing.core.client

import net.minecraft.CrashReport
import net.minecraft.ReportedException
import net.minecraft.client.Camera
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.QuadParticleGroup
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterParticleGroupsEvent
import work.nekow.particledrawing.ParticleDrawing

/**
 * 自定义粒子渲染分组：绕过原版 SINGLE_QUADS 分组每 group 16384 粒子的硬上限，
 * 让大批量动画粒子（如 5w）都能进入原版批量渲染管线（billboard quad + 纹理 + 光照 + 混合）。
 */
val BATCHED_QUADS: ParticleRenderType = ParticleRenderType("PARTICLE_DRAWING_BATCHED", "PD")

/**
 * 无粒子数上限的 quad 粒子分组，每粒子 extract 与提取流程都复用原版，只把渲染状态换成
 * [OrientedQuadRenderState]——原版状态每颗粒子只存一个尺寸，画不出「非等宽 + 平面内旋转」的定向线段。
 */
class BatchedQuadParticleGroup(
    engine: ParticleEngine,
    private val particleType: ParticleRenderType,
) : QuadParticleGroup(engine, particleType) {

    private val renderState = OrientedQuadRenderState()

    override fun add(particle: Particle): Boolean {
        // 本分组只装自己的桥接粒子（BATCHED_QUADS 只由 BridgeParticle.getGroup 返回）。
        // 屏掉别的粒子是为了 extractRenderState 里能直接读坐标——Particle.x/y/z 是 protected，
        // 只有 BridgeParticle 自己能读，别的包读不到。
        if (particle !is BridgeParticle) return false
        particles.add(particle)
        return true
    }

    /** 与原版 QuadParticleGroup 同一套提取逻辑（含崩溃归因），只是换了渲染状态。 */
    override fun extractRenderState(frustum: Frustum, camera: Camera, partialTickTime: Float): ParticleGroupRenderState {
        for (particle in particles) {
            val bridge = particle as BridgeParticle
            if (frustum.pointInFrustum(bridge.renderX(), bridge.renderY(), bridge.renderZ())) {
                try {
                    bridge.extract(renderState, camera, partialTickTime)
                } catch (t: Throwable) {
                    val report = CrashReport.forThrowable(t, "Rendering Particle")
                    val category = report.addCategory("Particle being rendered")
                    category.setDetail("Particle") { bridge.toString() }
                    category.setDetail("Particle Type") { particleType.toString() }
                    throw ReportedException(report)
                }
            }
        }
        return renderState
    }
}

@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object ParticleGroupRegistrar {
    @SubscribeEvent
    @JvmStatic
    fun onRegister(event: RegisterParticleGroupsEvent) {
        event.register(BATCHED_QUADS) { engine -> BatchedQuadParticleGroup(engine, BATCHED_QUADS) }
    }
}
