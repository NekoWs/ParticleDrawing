package work.nekow.particledrawing.core.client

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.ViewportEvent
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
     * 负责粒子引擎的延迟初始化及每帧更新（缓动插值 + 动态光照）。
     * 注意：编排动画程序的求值不在这里——渲染帧与 game tick 不同步，
     * 每帧重写桥接粒子的 xo/x 会把插值端点折叠成同值对，破坏 partialTick 扫掠。
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
    }

    /**
     * 玩家 game tick 事件处理（每 game tick，约 20Hz）。
     * 仅在本地玩家的 tick 里推进一次（PlayerTickEvent 对每个在场玩家各触发一次）：
     * - 编排动画程序求值：实体本 tick 移动完成后取值，每 tick 写一对干净的
     *   桥接插值端点，由渲染端 partialTick 平滑扫掠；
     * - 本地动画播放时间轴。
     */
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onPlayerTick(event: PlayerTickEvent.Post) {
        if (!event.entity.level().isClientSide) return
        val mc = net.minecraft.client.Minecraft.getInstance()
        if (event.entity !== mc.player) return
        ClientAnimationManager.tick()
        ClientAnimationProgramManager.tick()
    }

    /**
     * FOV 覆盖：摄像机预览模式下把玩家相机的视场角设为摄像机关键帧值。
     * `ComputeFov` 在 `Camera.update` 的 `calculateFov` 内触发，其结果写入 `camera.fov`，
     * 早于 `prepareCullFrustum` / `setupPerspective`，故覆盖能正确作用于投影矩阵。
     */
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onComputeFov(event: ViewportEvent.ComputeFov) {
        // 与 CameraMixin 的姿态插值同规则：按渲染 partialTick 在相邻 tick 间插值，FOV 不逐 tick 跳变
        val fov = CameraController.currentFov(event.partialTick) ?: return
        event.setFOV(fov)
    }
}
