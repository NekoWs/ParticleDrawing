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
        registrar.playToClient(ParticleRotationPayload.TYPE, ParticleRotationPayload.STREAM_CODEC, ClientPayloadHandler::handleRotation)
        registrar.playToClient(ParticleTranslatePayload.TYPE, ParticleTranslatePayload.STREAM_CODEC, ClientPayloadHandler::handleTranslate)
        registrar.playToClient(ParticleSetPositionPayload.TYPE, ParticleSetPositionPayload.STREAM_CODEC, ClientPayloadHandler::handleSetPosition)
        registrar.playToClient(ParticleLightLevelPayload.TYPE, ParticleLightLevelPayload.STREAM_CODEC, ClientPayloadHandler::handleLightLevel)
        registrar.playToClient(PlayAnimationPayload.TYPE, PlayAnimationPayload.STREAM_CODEC, ClientPayloadHandler::handlePlayAnimation)
        registrar.playToClient(VariableUpdatePayload.TYPE, VariableUpdatePayload.STREAM_CODEC, ClientPayloadHandler::handleVariableUpdate)
        registrar.playToClient(StopAnimationPayload.TYPE, StopAnimationPayload.STREAM_CODEC, ClientPayloadHandler::handleStopAnimation)

        // 动画文件同步（配置阶段）：客户端请求 → 服务器下发文件块 → 完成信号
        registrar.configurationToServer(AnimationSyncRequestPayload.TYPE, AnimationSyncRequestPayload.STREAM_CODEC, ServerPayloadHandler::handleSyncRequest)
        registrar.configurationToClient(AnimationSyncBeginPayload.TYPE, AnimationSyncBeginPayload.STREAM_CODEC, ClientPayloadHandler::handleSyncBegin)
        registrar.configurationToClient(AnimationSyncFilePayload.TYPE, AnimationSyncFilePayload.STREAM_CODEC, ClientPayloadHandler::handleSyncFile)
        registrar.configurationToClient(AnimationSyncDonePayload.TYPE, AnimationSyncDonePayload.STREAM_CODEC, ClientPayloadHandler::handleSyncDone)
    }
}
