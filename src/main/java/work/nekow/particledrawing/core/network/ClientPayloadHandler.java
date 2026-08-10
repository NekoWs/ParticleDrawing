package work.nekow.particledrawing.core.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import work.nekow.particledrawing.core.client.ClientParticleEngine;

final class ClientPayloadHandler {

    private ClientPayloadHandler() {}

    static void handleSpawn(ParticleSpawnPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientParticleEngine engine = ClientParticleEngine.instance();
            if (engine != null) {
                engine.spawnParticle(
                    payload.particleId(),
                    payload.style(),
                    payload.x(), payload.y(), payload.z(),
                    payload.r(), payload.g(), payload.b(), payload.a(),
                    payload.scale(),
                    payload.lifetime(),
                    payload.groupId(),
                    payload.glowing()
                );
            }
        });
    }

    static void handleUpdate(ParticleUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientParticleEngine engine = ClientParticleEngine.instance();
            if (engine != null) {
                engine.updateParticle(
                    payload.particleId(),
                    payload.x(), payload.y(), payload.z(),
                    payload.r(), payload.g(), payload.b(), payload.a(),
                    payload.scale(),
                    payload.hasPosition(),
                    payload.hasColor(),
                    payload.hasScale(),
                    payload.durationTicks(),
                    payload.easingType()
                );
            }
        });
    }

    static void handleDestroy(ParticleDestroyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientParticleEngine engine = ClientParticleEngine.instance();
            if (engine != null) {
                engine.destroyParticles(payload.particleIds());
            }
        });
    }

    static void handleGroupTransform(ParticleGroupTransformPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientParticleEngine engine = ClientParticleEngine.instance();
            if (engine != null) {
                engine.applyGroupTransform(
                    payload.groupId(),
                    payload.transformType(),
                    payload.dx(), payload.dy(), payload.dz(),
                    payload.ax(), payload.ay(), payload.az(), payload.radians(),
                    payload.r(), payload.g(), payload.b(), payload.a(),
                    payload.targetScale(),
                    payload.px(), payload.py(), payload.pz(),
                    payload.durationTicks(),
                    payload.easingType()
                );
            }
        });
    }
}
