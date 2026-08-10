package work.nekow.particledrawing.core.network

import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.core.client.ClientParticleEngine

/**
 * 客户端数据包处理器，将网络数据包路由到对应客户端引擎方法。
 */
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

    fun handleContinuousRotation(payload: ContinuousRotationPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance()
            if (engine != null) {
                engine.setContinuousRotation(
                    payload.groupId,
                    payload.active,
                    payload.ax, payload.ay, payload.az,
                    payload.radiansPerTick,
                    payload.px, payload.py, payload.pz
                )
            }
        }
    }
}
