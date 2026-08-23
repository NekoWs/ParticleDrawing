package work.nekow.particledrawing.core.client

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.lighting.DynamicLightManager

/**
 * 客户端粒子渲染处理器。
 * - 每渲染帧：更新粒子引擎缓动状态并刷新动态光照；
 * - 每 game tick：推进本地动画播放（20Hz，避免按渲染帧推进导致的 3 倍计算量与速度漂移）。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
@Suppress("unused")
object ParticleRenderHandler {

    private var engineInitialized = false

    /**
     * 客户端渲染帧 Tick 事件处理。
     * 负责粒子引擎的延迟初始化及每帧更新（缓动插值 + 动态光照 + 编排动画程序求值）。
     */
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onClientTick(event: ClientTickEvent.Post) {
        if (!engineInitialized) {
            ClientParticleEngine.init()
            engineInitialized = true
        }

        val engine = ClientParticleEngine.instance()
        if (engine != null) {
            engine.frameUpdate()
            DynamicLightManager.renderDynamicLights(engine)
        }
        ClientAnimationProgramManager.tick()
    }

    /**
     * 玩家 game tick 事件处理（每 game tick，约 20Hz）。
     * 推进本地动画播放的当前时间轴并同步渲染。
     */
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onPlayerTick(event: PlayerTickEvent.Post) {
        if (event.entity.level().isClientSide) {
            ClientAnimationManager.tick()
        }
    }
}
