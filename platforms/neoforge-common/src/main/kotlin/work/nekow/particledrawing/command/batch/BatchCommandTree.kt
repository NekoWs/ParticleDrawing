package work.nekow.particledrawing.command.batch

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.CommandParticleRecipes
import work.nekow.particledrawing.command.ParticleCommandOptions
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.failureComponent
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.util.ParticleUtils
import java.util.concurrent.CompletableFuture

/**
 * `/pdraw particle batch <名字> kind=... <键>=<值>...`：一条命令写完一份发射器、一组一次性粒子或一条组时间线。
 *
 * 解析、校验、落地分三步；任何一步失败都不会改到现有配置。
 */
internal object BatchCommandTree {

    private val NAME = Regex("[A-Za-z0-9_.:-]{1,64}")

    fun tree(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("batch")
        .executes(::help)
        .then(Commands.literal("help")
            .executes(::help)
            .then(Commands.argument("group", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(BatchKeys.groups, builder) }
                .executes(::help)))
        .then(Commands.argument("name", StringArgumentType.word())
            .suggests(::suggestNames)
            .then(Commands.argument("options", StringArgumentType.greedyString())
                .suggests(::suggestOptions)
                .executes(::execute)))

    private fun execute(ctx: CommandContext<CommandSourceStack>): Int = try {
        val name = StringArgumentType.getString(ctx, "name")
        requireLocalized(NAME.matches(name)) { translation("error.recipe.name") }
        val rawOptions = StringArgumentType.getString(ctx, "options")
        val tokens = BatchOptions.parse(rawOptions)
        val kinds = tokens.filter { it.key == "kind" }
        requireLocalized(kinds.size == 1) { translation("error.batch.kind_count") }
        val kindRaw = BatchOptions.text(kinds[0].value)
        val kind = BatchKind.of(kindRaw) ?: throw LocalizedArgumentException(translation("error.batch.kind", kindRaw))
        val dryrun = tokens.firstOrNull { it.key == "dryrun" }?.let { BatchOptions.bool(it.value, it.key) } ?: false
        val dimension = ParticleUtils.dimensionUUID(ctx.source.level)
        when (kind) {
            BatchKind.EMITTER -> emitter(ctx, name, tokens, dimension, dryrun)
            BatchKind.SHAPE -> shape(ctx, name, kindRaw.lowercase(), tokens, dimension, dryrun)
            BatchKind.PROGRAM -> program(ctx, name, tokens, dimension, dryrun)
        }
    } catch (error: Exception) {
        org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").info(
            "[PD-batch] failed: {}: {}", error.javaClass.simpleName, error.message,
        )
        ctx.source.sendFailure(error.failureComponent("error.batch.failed"))
        0
    }

    private fun emitter(
        ctx: CommandContext<CommandSourceStack>, name: String, tokens: List<BatchToken>, dimension: java.util.UUID, dryrun: Boolean,
    ): Int {
        val existing = CommandParticleRecipes.names(dimension).takeIf { name in it }?.let { CommandParticleRecipes.get(dimension, name) }
        val plan = BatchEmitter.parse(tokens, existing, ctx.source)
        BatchEmitter.validate(plan, ctx.source.level, dimension, name, existing)
        if (dryrun) {
            val seeds = plan.sample(ctx.source.level).size
            ctx.source.sendSuccess(
                {
                    translation("command.batch.dryrun.emitter", name, plan.mode, seeds, plan.recipe.maxAlive).component()
                },
                false,
            )
            return 1
        }
        val draft = BatchEmitter.commit(name, plan, ctx.source.level, dimension, existing, ctx.source)
        ctx.source.sendSuccess(
            {
                translation("command.batch.emitter", name, plan.mode, draft.recipe.seeds.size, plan.recipe.maxAlive).component()
            },
            false,
        )
        return 1
    }

    private fun shape(
        ctx: CommandContext<CommandSourceStack>, name: String, shape: String, tokens: List<BatchToken>,
        dimension: java.util.UUID, dryrun: Boolean,
    ): Int {
        val plan = BatchShapes.parse(shape, tokens, ctx.source)
        BatchShapes.validate(plan, dimension, name)
        if (dryrun) {
            ctx.source.sendSuccess(
                { translation("command.batch.dryrun.shape", name, shape, plan.expected).component() },
                false,
            )
            return 1
        }
        val entry = BatchShapes.commit(plan, ctx.source.level, dimension, name)
        ctx.source.sendSuccess(
            { translation("command.batch.shape", name, shape, plan.expected, entry.group.size()).component() },
            false,
        )
        return plan.expected
    }

    private fun program(
        ctx: CommandContext<CommandSourceStack>, name: String, tokens: List<BatchToken>, dimension: java.util.UUID, dryrun: Boolean,
    ): Int {
        val plan = BatchProgram.parse(tokens, ctx.source)
        BatchProgram.validate(plan, dimension, name)
        if (dryrun) {
            ctx.source.sendSuccess(
                { translation("command.batch.dryrun.program", name, plan.actions.size).component() },
                false,
            )
            return 1
        }
        val count = BatchProgram.commit(plan, dimension, name, ctx.source)
        ctx.source.sendSuccess(
            { translation("command.batch.program", name, count).component() },
            false,
        )
        return count
    }

    private fun help(ctx: CommandContext<CommandSourceStack>): Int {
        val section = try {
            StringArgumentType.getString(ctx, "group")
        } catch (_: IllegalArgumentException) {
            null
        }
        ctx.source.sendSuccess({ translation("command.batch.usage").component() }, false)
        ctx.source.sendSuccess({ translation("command.batch.examples").component() }, false)
        val keys = if (section == null) BatchKeys.all else BatchKeys.all.filter { it.group.equals(section, ignoreCase = true) }
        if (keys.isEmpty()) {
            ctx.source.sendSuccess({ translation("command.batch.group_missing", section ?: "").component() }, false)
            return 1
        }
        for ((group, entries) in keys.groupBy { it.group }) {
            ctx.source.sendSuccess({ Component.literal("[$group]") }, false)
            for (entry in entries) {
                val line = if (entry.repeatable) "${entry.name}=${entry.syntax}（可重复）" else "${entry.name}=${entry.syntax}"
                ctx.source.sendSuccess({ Component.literal("  $line") }, false)
            }
        }
        return keys.size
    }

    private fun suggestNames(
        ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val dimension = ParticleUtils.dimensionUUID(ctx.source.level)
        val names = CommandParticleRecipes.names(dimension) + CommandParticleGroups.names(dimension)
        return SharedSuggestionProvider.suggest(names.distinct().map(StringArgumentType::escapeIfRequired), builder)
    }

    /** 只补当前正在输入的那一段：键名、`kind=`/枚举值、`program.N=` 的动作名。 */
    private fun suggestOptions(
        ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val raw = try {
            StringArgumentType.getString(ctx, "options")
        } catch (_: IllegalArgumentException) {
            ""
        }
        val prefix = raw.substringAfterLast(' ')
        val before = raw.substring(0, raw.length - prefix.length)
        val current = runCatching { BatchOptions.parse(before) }.getOrDefault(emptyList())
        val kind = current.firstOrNull { it.key == "kind" }?.let { BatchKind.of(BatchOptions.text(it.value)) }
        val suggestions = LinkedHashSet<String>()
        when {
            '=' !in prefix -> {
                val keys = (kind?.let(BatchKeys::keysFor) ?: BatchKeys.all)
                    .map { it.name.replace(Regex("<[^>]+>"), "") }
                suggestions += keys.filter { it.startsWith(prefix, ignoreCase = true) }
                suggestions += "kind="
            }
            prefix.substringBefore('=') == "kind" -> {
                suggestions += "emitter"
                suggestions += "program"
                suggestions += BatchKind.SHAPES
            }
            else -> {
                val key = prefix.substringBefore('=')
                val spec = BatchKeys.exact(key) ?: BatchKeys.dynamic(key, kind ?: BatchKind.EMITTER)
                spec?.choices?.let { suggestions += it }
                when {
                    key.startsWith("program.") && key.removePrefix("program.").toIntOrNull() != null ->
                        suggestions += BatchKeys.actionNames
                    key == "morph.easing" -> suggestions += BatchKeys.easingNames
                    key.endsWith("easing") -> suggestions += BatchKeys.easingNames
                }
            }
        }
        val filtered = suggestions.filter { it.isNotBlank() && it.startsWith(prefix.substringAfter('='), ignoreCase = true) }
        return SharedSuggestionProvider.suggest(filtered, builder.createOffset(builder.input.lastIndexOf(' ') + 1))
    }
}



