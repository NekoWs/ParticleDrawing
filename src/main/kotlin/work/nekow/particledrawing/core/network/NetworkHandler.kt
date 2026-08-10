package work.nekow.particledrawing.core.network

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import work.nekow.particledrawing.ParticleDrawing

@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object NetworkHandler {

    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar: PayloadRegistrar = event.registrar("1")

        registrar.playToClient(
            ParticleSpawnPayload.TYPE,
            ParticleSpawnPayload.STREAM_CODEC,
            ClientPayloadHandler::handleSpawn
        )

        registrar.playToClient(
            ParticleUpdatePayload.TYPE,
            ParticleUpdatePayload.STREAM_CODEC,
            ClientPayloadHandler::handleUpdate
        )

        registrar.playToClient(
            ParticleDestroyPayload.TYPE,
            ParticleDestroyPayload.STREAM_CODEC,
            ClientPayloadHandler::handleDestroy
        )

        registrar.playToClient(
            ParticleGroupTransformPayload.TYPE,
            ParticleGroupTransformPayload.STREAM_CODEC,
            ClientPayloadHandler::handleGroupTransform
        )
    }
}
