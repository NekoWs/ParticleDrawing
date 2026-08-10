package work.nekow.particledrawing.core.network

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import work.nekow.particledrawing.ParticleDrawing

/**
 * 网络数据包注册器，将粒子相关的 playToClient 数据包注册到 NeoForge 网络管道。
 */
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
