package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/**
 * `/pdraw particle program`：命名程序模板。
 *
 * 模板只保存参数与 `then` 动作原文；`then call <模板> …` 在服务端展开时替换参数再走同一套解析，
 * 因此参数可以出现在公式、文字、数字与点源名称里，客户端仍然只收到展开后的线性指令。
 */
internal object ProgramTemplateCommands {
    private const val MAX_PARAMETERS = 16
    private val PARAMETER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun parse(input: CommandInput, source: CommandSourceStack): () -> Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        return when (input.choice(listOf("define", "list", "inspect", "delete"))) {
            "define" -> define(input, source, dimension)
            "inspect" -> inspect(input, source, dimension)
            "delete" -> delete(input, source, dimension)
            else -> list(source, dimension)
        }
    }

    private fun define(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val name = input.name(ProgramTemplates.names(dimension))
        val parameters = linkedMapOf<String, String>()
        while (input.take("with")) {
            if (parameters.size == MAX_PARAMETERS) input.invalid("up to $MAX_PARAMETERS with")
            val key = input.value("parameter")
            if (!PARAMETER.matches(key)) input.invalid("parameter name", key)
            if (key in parameters) input.invalid("distinct parameter", key)
            parameters[key] = input.value("default")
        }
        val body = input.consumeRemaining()
        if (body.isBlank()) input.invalid("then")
        if (!body.startsWith("then")) input.invalid("then", body.take(16))
        // 定义时就检查动作语法：先用缺省参数（r 取 0）替换占位符，保证数字位也能解析；
        // 实参不同导致的错误在展开期再报。
        ProgramCommands.parseTemplateBody(
            ProgramCommands.substitute(body, parameters + ("r" to "0")),
        )
        return {
            ProgramTemplates.checkCapacity(dimension, name)
            ProgramTemplates.define(dimension, name, parameters, body)
            EmitterCommands.success(source, name, "define")
        }
    }

    private fun inspect(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val name = input.name(ProgramTemplates.names(dimension))
        input.finish()
        return {
            val template = ProgramTemplates.get(dimension, name)
            val parameters = template.parameters.entries.joinToString(", ") { "${it.key}=${it.value}" }
            source.reply({
                translation("command.v2.program.info", name, template.body.length, parameters.ifEmpty {
                    translation("command.v2.program.none")
                }).component()
            })
            1
        }
    }

    private fun delete(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val pattern = input.namePattern(ProgramTemplates.names(dimension))
        input.finish()
        return {
            val names = pattern.select(ProgramTemplates.names(dimension))
            ProgramTemplates.remove(dimension, names)
            EmitterCommands.success(source, pattern, "delete", names.size)
        }
    }

    private fun list(source: CommandSourceStack, dimension: UUID): () -> Int =
        { EmitterCommands.success(source, ProgramTemplates.names(dimension).joinToString(", "), "list") }
}
