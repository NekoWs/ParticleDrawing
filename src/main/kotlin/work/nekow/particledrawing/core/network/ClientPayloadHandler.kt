package work.nekow.particledrawing.core.network

import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.core.client.ClientParticleEngine

internal object ClientPayloadHandler {

    fun handleSpawn(payload: ParticleSpawnPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance()
            if (engine != null) {
                engine.spawnParticle(
                    payload.particleId,
                    payload.style,
                    payload.x, payload.y, payload.z,
                    payload.r, payload.g, payload.b, payload.a,
                    payload.scale,
                    payload.lifetime,
                    payload.groupId,
                    payload.glowing
                )
            }
        }
    }

    fun handleUpdate(payload: ParticleUpdatePayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance()
            if (engine != null) {
                engine.updateParticle(
                    payload.particleId,
                    payload.x, payload.y, payload.z,
                    payload.r, payload.g, payload.b, payload.a,
                    payload.scale,
                    payload.hasPosition,
                    payload.hasColor,
                    payload.hasScale,
                    payload.durationTicks,
                    payload.easingType()
                )
            }
        }
    }

    fun handleDestroy(payload: ParticleDestroyPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance()
            if (engine != null) {
                engine.destroyParticles(payload.particleIds)
            }
        }
    }

    fun handleGroupTransform(payload: ParticleGroupTransformPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance()
            if (engine != null) {
                engine.applyGroupTransform(
                    payload.groupId,
                    payload.transformType,
                    payload.dx, payload.dy, payload.dz,
                    payload.ax, payload.ay, payload.az, payload.radians,
                    payload.r, payload.g, payload.b, payload.a,
                    payload.targetScale,
                    payload.px, payload.py, payload.pz,
                    payload.durationTicks,
                    payload.easingType()
                )
            }
        }
    }
}
