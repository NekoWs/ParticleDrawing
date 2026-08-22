package work.nekow.particledrawing.core.server

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener
import net.minecraft.resources.Identifier
import net.minecraft.server.network.ConfigurationTask
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask
import work.nekow.particledrawing.core.network.AnimationSyncBeginPayload
import java.util.function.Consumer

/**
 * 服务器端配置阶段任务：动画文件同步。
 *
 * 注册于 [RegisterConfigurationTasksEvent]，在配置阶段向客户端发送「同步开始」信号，
 * 真正的结束由服务器收到客户端请求并下发完差异文件后在 [work.nekow.particledrawing.core.network.ServerPayloadHandler]
 * 中通过 `ServerPayloadContext.finishCurrentTask` 完成（客户端禁止调用）。
 */
class AnimationSyncConfigTask(
    private val listener: ServerConfigurationPacketListener,
) : ICustomConfigurationTask {

    override fun run(consumer: Consumer<CustomPacketPayload>) {
        consumer.accept(AnimationSyncBeginPayload)
    }

    override fun type(): ConfigurationTask.Type = TYPE

    companion object {
        val ID: Identifier = Identifier.fromNamespaceAndPath("particledrawing", "animation_sync")
        val TYPE: ConfigurationTask.Type = ConfigurationTask.Type(ID.toString())
    }
}
