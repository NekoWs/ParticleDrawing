package work.nekow.particledrawing.core.network

import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.core.client.ClientParticleEngine
import work.nekow.particledrawing.core.motion.MotionPayload

/**
 * 客户端数据包处理器，将各类数据包分发到 [ClientParticleEngine] 的对应方法。
 */
internal object ClientPayloadHandler {

    fun handleSpawn(payload: ParticleSpawnPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.spawnParticle(
                payload.particleId, payload.style,
                payload.x, payload.y, payload.z,
                payload.r, payload.g, payload.b, payload.a,
                payload.scale, payload.lifetime,
                payload.groupId, payload.glowing, payload.lightLevel
            )
        }
    }

    fun handleUpdate(payload: ParticleUpdatePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.updateParticle(
                payload.particleId,
                payload.x, payload.y, payload.z,
                payload.r, payload.g, payload.b, payload.a,
                payload.scale,
                payload.hasPosition, payload.hasColor, payload.hasScale,
                payload.durationTicks, payload.easingType()
            )
        }
    }

    fun handleDestroy(payload: ParticleDestroyPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.destroyParticles(payload.particleIds)
        }
    }

    fun handleGroupTransform(payload: ParticleGroupTransformPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.applyGroupTransform(
                payload.groupId, payload.transformType,
                payload.dx, payload.dy, payload.dz,
                payload.ax, payload.ay, payload.az, payload.radians,
                payload.r, payload.g, payload.b, payload.a,
                payload.targetScale,
                payload.px, payload.py, payload.pz,
                payload.durationTicks, payload.easingType()
            )
        }
    }

    fun handleMotion(payload: MotionPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.addMotion(
                payload.groupId, payload.active,
                payload.algorithmId, payload.params,
                payload.px, payload.py, payload.pz
            )
        }
    }

    fun handleVelocity(payload: ParticleVelocityPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.setVelocity(
                payload.particleId, payload.vx, payload.vy, payload.vz
            )
        }
    }

    fun handleLightLevel(payload: ParticleLightLevelPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.setLightLevel(
                payload.particleId, payload.lightLevel
            )
        }
    }
}
