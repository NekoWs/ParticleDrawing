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
 * 配置阶段向客户端发送「同步开始」信号；结束由服务端收到客户端请求、下发完差异文件后
 * 在 [work.nekow.particledrawing.core.network.ServerPayloadHandler] 中调用
 * `ServerPayloadContext.finishCurrentTask` 完成。
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
