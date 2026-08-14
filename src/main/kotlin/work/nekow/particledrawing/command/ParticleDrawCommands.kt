package work.nekow.particledrawing.command

import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import work.nekow.particledrawing.ParticleDrawing

/**
 * 命令注册。当前仅保留 /test 命令，供后续功能接入。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object ParticleDrawCommands {

    /**
     * 注册命令。
     * @param event 命令注册事件
     */
    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val dispatcher = event.dispatcher

        dispatcher.register(
            Commands.literal("test")
                .executes(::runTest)
        )
    }

    /**
     * /test 命令执行逻辑。
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private fun runTest(ctx: CommandContext<CommandSourceStack>): Int {
        // TODO
        return 1
    }
}
