package work.nekow.particledrawing.core.client

import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.QuadParticleGroup
import net.minecraft.client.particle.SingleQuadParticle
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
 * 无粒子数上限的 quad 粒子分组，其余渲染逻辑（extractRenderState / 每粒子 extract）复用原版。
 */
class BatchedQuadParticleGroup(engine: ParticleEngine, type: ParticleRenderType) :
    QuadParticleGroup(engine, type) {

    override fun add(particle: Particle): Boolean {
        particles.add(particle as SingleQuadParticle)
        return true
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
