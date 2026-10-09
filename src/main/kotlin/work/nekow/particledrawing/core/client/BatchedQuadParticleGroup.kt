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
 * 自定义粒子渲染分组：绕开原版 SINGLE_QUADS 分组每 group 的粒子数硬上限，
 * 让大批量动画粒子也能进入原版批量渲染管线。
 */
val BATCHED_QUADS: ParticleRenderType = ParticleRenderType("PARTICLE_DRAWING_BATCHED", "PD")

/**
 * 无粒子数上限的 quad 粒子分组：提取流程复用原版，渲染状态换成 [OrientedQuadRenderState]，
 * 后者按宽高两个尺寸出图，能画定向线段。
 */
class BatchedQuadParticleGroup(
    engine: ParticleEngine,
    private val particleType: ParticleRenderType,
) : QuadParticleGroup(engine, particleType) {

    private val renderState = OrientedQuadRenderState()

    override fun add(particle: Particle): Boolean {
        // 本分组只装自己的桥接粒子（BATCHED_QUADS 只由 BridgeParticle.getGroup 返回）。
        // 别处的粒子要在 extractRenderState 里读坐标，而 Particle.x/y/z 是 protected，读不到。
        if (particle !is BridgeParticle) return false
        particles.add(particle)
        return true
    }

    /** 与原版 QuadParticleGroup 同一套提取逻辑（含崩溃归因），只换了渲染状态。 */
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
