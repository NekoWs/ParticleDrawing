package work.nekow.particledrawing.command.v2

import work.nekow.particledrawing.i18n.replyFailure

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import org.apache.logging.log4j.LogManager
import work.nekow.particledrawing.i18n.LocalizedFailure
import work.nekow.particledrawing.i18n.failureComponent

internal object ParticleCommandsV2 {
    private val LOGGER = LogManager.getLogger("ParticleDrawing")
    private const val INPUT = "declaration"
    val usages = linkedMapOf(
        "draw" to "draw <shape> [as <group>] ... [stagger <ticks>] [enter <style> <ticks>] [also <shape> ...] [then <action> ...] [dryrun]",
        "emitter" to "emitter <name> ... [start|burst [mode]] [dryrun]",
        "pointset" to "pointset define|list|inspect|delete ...",
        "template" to "template <name> [from emitter <source>|...] [dryrun]",
        "use" to "use <template> as <name> [without <path>] ... [start|burst [mode]] [dryrun]",
        "start" to "start <emitter> [standard|instanced|synced] [dryrun]",
        "burst" to "burst <emitter> [standard|instanced|gpu|synced] [dryrun]",
        "bind" to "bind <name> [emit <template> [mode <mode>]] [on <event> burst <template> [mode <mode>] [each] [stop]] ...",
        "unbind" to "unbind <name|pattern>",
        "stop" to "stop <emitter|pattern>",
        "clear" to "clear emitter|group <name|pattern>",
        "delete" to "delete emitter|group|template <name|pattern>",
        "advanced" to "advanced emitter|group ...",
    )

    fun register(root: LiteralArgumentBuilder<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> {
        for (verb in usages.keys) {
            root.then(Commands.literal(verb).then(Commands.argument(INPUT, StringArgumentType.greedyString())
                .suggests { ctx, builder ->
                    val input = CommandInput(builder.remaining, suggesting = true, suggestionSource = ctx.source)
                    try {
                        parse(verb, input, ctx.source)
                    } catch (_: CommandSyntaxException) {
                        // 不完整的参数由同一解析器给出候选。
                    } catch (_: RuntimeException) {
                        // 保留出错位置之前的候选。
                    }
                    input.buildSuggestions(builder, ctx)
                }
                .executes { ctx ->
                    try {
                        parse(verb, CommandInput(StringArgumentType.getString(ctx, INPUT)), ctx.source)()
                    } catch (error: CommandSyntaxException) {
                        throw error
                    } catch (error: RuntimeException) {
                        // 参数或状态错误是给执行者的正常反馈，只写一行文本，不带堆栈；
                        // 命令方块每刻重试也不会刷屏。真正的意外异常仍留调试堆栈。
                        if (error is LocalizedFailure) {
                            LOGGER.trace("[PD-command] {} rejected: {}", verb, error.message)
                        } else {
                            LOGGER.debug("[PD-command] {} failed: {}", verb, error.message ?: error.javaClass.simpleName, error)
                        }
                        ctx.source.replyFailure(error.failureComponent("error.command.v2.failed"))
                        0
                    }
                }))
        }
        return root
    }

    internal fun checkSyntax(command: String, source: CommandSourceStack) {
        val body = command.substringAfter("pdraw particle ")
        val verb = body.substringBefore(' ')
        if (verb in setOf("field", "help")) return
        require(verb in usages) { "Unknown particle command: $verb" }
        parse(verb, CommandInput(body.substringAfter(' ', "")), source)
    }

    private fun parse(verb: String, input: CommandInput, source: CommandSourceStack): () -> Int =
        when (verb) {
            "draw" -> DrawCommands.parse(input, source)
            "pointset" -> PointSetCommands.parse(input, source)
            "bind", "unbind" -> EntityBindingCommands.parse(verb, input, source)
            "advanced" -> when (input.choice(listOf("emitter", "group"))) {
                "group" -> DrawCommands.advanced(input, source)
                else -> EmitterCommands.parse(verb, input, source)
            }
            "clear", "delete" -> {
                val kinds = if (verb == "delete") listOf("emitter", "group", "template") else listOf("emitter", "group")
                val kind = input.choice(kinds)
                if (kind == "group") DrawCommands.control(source, verb, input)
                else EmitterCommands.control(source, verb, kind, input)
            }
            else -> EmitterCommands.parse(verb, input, source)
        }

}
