package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.event.ViewportEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.lighting.DynamicLightManager

// 客户端粒子渲染处理器：每 game tick 更新粒子引擎缓动（速度积分与缓动轮转对时间敏感，不能按渲染帧推进）；
// 每渲染帧推进函数对象 process 并同步帧级同步派生粒子、刷新动态光照；
// 普通粒子与编排动画程序仍按 game tick 推进（20Hz，避免按渲染帧全量重算的 3 倍计算量与速度漂移）。
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
@Suppress("unused")
object ParticleRenderHandler {

    private var engineInitialized = false

    // 上次推进引擎时的关卡 gameTime：还是同一个值就说明这一 tick 关卡没走，引擎也不许动
    private var lastLevelGameTime = Long.MIN_VALUE

    /**
     * 客户端 game tick 事件处理（约 20Hz）。
     * 负责粒子引擎的延迟初始化与缓动同步：速度积分与缓动轮转对时间敏感，
     * 只能按 game tick 推进，不能按渲染帧重写桥接粒子的 xo/x——渲染帧与
     * game tick 不同步，每帧改写会把插值端点折叠成同值对、破坏 partialTick 扫掠。
     *
     * 只在该 tick 关卡真的走过时推进。单人按 Esc 暂停时关卡不 tick（原版 particleEngine.tick
     * 同样被跳过），引擎若照跑，速度/力驱动的粒子会在服务端冻结期间沿最后速度继续外推、飘出范围。
     */
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onClientTick(event: ClientTickEvent.Post) {
        if (!engineInitialized) {
            ClientParticleEngine.init()
            engineInitialized = true
        }

        val level = Minecraft.getInstance().level ?: return
        val gameTime = level.gameTime
        if (gameTime == lastLevelGameTime) return
        lastLevelGameTime = gameTime

        ClientParticleEngine.instance()?.frameUpdate()
    }

    /**
     * 每渲染帧推进函数对象 process 并同步帧级同步派生粒子；再刷新动态光照，
     * 使光源位置使用本帧最新粒子位置。普通粒子不在此路径推进。
     */
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onRenderFrame(event: RenderFrameEvent.Pre) {
        val partialTick = event.partialTick.getGameTimeDeltaPartialTick(false)
        ClientAnimationManager.frameTick(partialTick)
        // 运行时发射器：按渲染帧（不是 tick）推进里程/时间，出现与淡出都是逐帧的
        ClientEmitterManager.frameTick(partialTick)

        val engine = ClientParticleEngine.instance() ?: return
        // 带寿命曲线的粒子：淡出/收缩逐帧刷新（只改颜色/缩放，不动插值端点）
        engine.frameSyncCurves()
        DynamicLightManager.renderDynamicLights(engine)
    }

    // 玩家 game tick 事件（约 20Hz）。只在本地玩家的 tick 里推进一次（PlayerTickEvent 对每个在场玩家各触发一次）。
    // 编排动画程序在实体本 tick 移动完成后取值，写一对干净的桥接插值端点，交给渲染端 partialTick 平滑扫掠；同时推进本地动画时间轴。
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onPlayerTick(event: PlayerTickEvent.Post) {
        if (!event.entity.level().isClientSide) return
        val mc = Minecraft.getInstance()
        if (event.entity !== mc.player) return
        ClientAnimationManager.tick()
        ClientAnimationProgramManager.tick()
    }

    /**
     * 客户端世界卸载（切换维度/重生/退出）：旧 ClientLevel 的 ParticleEngine 随之销毁，
     * 本地动画播放的桥接粒子不可恢复——清空播放条目，等服务端在玩家到达新世界后
     * 重发 PlayAnimationPayload 再重建（见 ServerAnimationManager.syncPlaybacksToPlayer）。
     */
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onClientLevelUnload(event: LevelEvent.Unload) {
        if (event.level is ClientLevel) {
            // 换关卡后 gameTime 从新世界重新计，旧值留着会误判成「本 tick 没走」
            lastLevelGameTime = Long.MIN_VALUE
            ClientAnimationManager.onClientLevelUnload()
            // 发射器声明随旧关卡一起作废：服务端会在新维度补发
            ClientEmitterManager.clearAll()
        }
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
        event.fov = fov
    }
}
