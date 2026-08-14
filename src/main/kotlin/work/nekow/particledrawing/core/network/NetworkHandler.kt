package work.nekow.particledrawing.core.network

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.core.motion.MotionPayload

/**
 * 网络包注册处理器：注册所有 `playToClient` 数据包。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object NetworkHandler {

    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar: PayloadRegistrar = event.registrar("1")

        registrar.playToClient(ParticleSpawnPayload.TYPE, ParticleSpawnPayload.STREAM_CODEC, ClientPayloadHandler::handleSpawn)
        registrar.playToClient(ParticleUpdatePayload.TYPE, ParticleUpdatePayload.STREAM_CODEC, ClientPayloadHandler::handleUpdate)
        registrar.playToClient(ParticleDestroyPayload.TYPE, ParticleDestroyPayload.STREAM_CODEC, ClientPayloadHandler::handleDestroy)
        registrar.playToClient(ParticleGroupTransformPayload.TYPE, ParticleGroupTransformPayload.STREAM_CODEC, ClientPayloadHandler::handleGroupTransform)
        registrar.playToClient(MotionPayload.TYPE, MotionPayload.STREAM_CODEC, ClientPayloadHandler::handleMotion)
        registrar.playToClient(ParticleVelocityPayload.TYPE, ParticleVelocityPayload.STREAM_CODEC, ClientPayloadHandler::handleVelocity)
    }
}
