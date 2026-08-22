package work.nekow.particledrawing

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.server.AnimationSyncConfigTask

/**
 * ParticleDrawing 模组入口，在构造时注册服务端与客户端配置。
 */
@Mod(ParticleDrawing.MODID)
@Suppress("unused")
class ParticleDrawing(bus: IEventBus, container: ModContainer) {
    companion object {
        const val MODID = "particledrawing"
    }

    init {
        container.registerConfig(ModConfig.Type.SERVER, ParticleDrawingConfig.SERVER_SPEC)
        container.registerConfig(ModConfig.Type.CLIENT, ParticleDrawingConfig.CLIENT_SPEC)

        // 动画文件同步任务（配置阶段，服务器向客户端下发动画 + 贴图）
        bus.addListener(::onRegisterConfigurationTasks)
    }

    private fun onRegisterConfigurationTasks(event: RegisterConfigurationTasksEvent) {
        event.register(AnimationSyncConfigTask(event.listener))
    }
}
