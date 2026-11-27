package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import work.nekow.particledrawing.api.ForceFieldKind
import work.nekow.particledrawing.api.ParticleForceField
import work.nekow.particledrawing.api.ParticleForceFields
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.command.v2.CommandInput
import java.util.concurrent.CompletableFuture
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.replyFailure
import work.nekow.particledrawing.i18n.failureComponent
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.platform.PlatformCompat
import work.nekow.particledrawing.platform.toCore

internal object ParticleFieldCommands {
    fun tree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("field").requires(PlatformCompat.gamemastersOnly())
        for (kind in ForceFieldKind.entries) {
            val strength = Commands.argument("strength", DoubleArgumentType.doubleArg(-4.0, 4.0))
            if (kind == ForceFieldKind.WIND || kind == ForceFieldKind.VORTEX) {
                strength.then(Commands.argument("direction", ParticleCommandArguments.textType()).executes { ctx ->
                    configure(ctx, kind)
                })
            } else {
                strength.executes { ctx -> configure(ctx, kind) }
            }
            root.then(Commands.literal(kind.name.lowercase()).then(Commands.argument("id", StringArgumentType.string()).suggests(::suggestIds)
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.05, 128.0)).then(strength))))
        }
        root.then(Commands.literal("anchor").then(Commands.argument("id", StringArgumentType.string()).suggests(::suggestIds)
            .then(Commands.literal("fixed").then(Commands.argument("position", Vec3Argument.vec3()).executes { ctx -> safe(ctx, "command.field.anchored") {
                ParticleForceFields.anchor(ctx.source.level, id(ctx), RecipeAnchor(Vec3Argument.getVec3(ctx, "position")))
            } }))
            .then(Commands.literal("entity").then(Commands.argument("entity", EntityArgument.entity()).executes { ctx -> safe(ctx, "command.field.anchored") {
                val entity = EntityArgument.getEntity(ctx, "entity")
                ParticleForceFields.anchor(ctx.source.level, id(ctx), RecipeAnchor(entity.position(), entity.uuid, entity.id, local = true))
            } }))))
        root.then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.greedyString())
            .suggests { ctx, builder ->
                SharedSuggestionProvider.suggest(ParticleForceFields.ids(ctx.source.level).map { it.toString() } + "*", builder)
            }.executes(::remove)))
        root.then(Commands.literal("list").executes { ctx ->
            val ids = ParticleForceFields.ids(ctx.source.level)
            ctx.source.reply({ translation("command.field.list", ids.joinToString(", ")).component() })
            ids.size
        })
        return root
    }

    private fun suggestIds(
        ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(ParticleForceFields.ids(ctx.source.level).map { it.toString() }, builder)

    private fun configure(ctx: CommandContext<CommandSourceStack>, kind: ForceFieldKind) = safe(ctx) {
        val id = id(ctx)
        val direction = if (kind == ForceFieldKind.WIND || kind == ForceFieldKind.VORTEX) {
            ParticleCommandArguments.vector(word(ctx, "direction")).toCore()
        } else Vec3.ZERO
        val spec = ParticleForceField(
            kind, DoubleArgumentType.getDouble(ctx, "radius"), DoubleArgumentType.getDouble(ctx, "strength"), direction,
            seed = id.toString().hashCode(),
        )
        ParticleForceFields.set(ctx.source.level, id, spec, RecipeAnchor(ctx.source.position))
    }

    private fun remove(ctx: CommandContext<CommandSourceStack>): Int {
        val input = CommandInput(word(ctx, "id"))
        val pattern = input.namePattern(emptyList())
        input.finish()
        val level = ctx.source.level
        if (!pattern.isWildcard) return safe(ctx, "command.field.removed", pattern.text) {
            val id = ResourceId.parse(pattern.text)
            requireLocalized(ParticleForceFields.remove(level, id)) { translation("error.field.not_found", id) }
        }
        val ids = ParticleForceFields.ids(level).filter { pattern.matches(it.toString()) }
        val count = ParticleForceFields.removeAll(level, ids)
        return pattern.report(ctx.source, count)
    }

    private fun id(ctx: CommandContext<CommandSourceStack>) = ResourceId.parse(word(ctx, "id"))
    private fun word(ctx: CommandContext<CommandSourceStack>, key: String) = StringArgumentType.getString(ctx, key)

    private inline fun safe(
        ctx: CommandContext<CommandSourceStack>, successKey: String = "command.field.updated",
        rawId: String = word(ctx, "id"), action: () -> Unit,
    ): Int = try {
        action()
        ctx.source.reply({ translation(successKey, ResourceId.parse(rawId)).component() })
        1
    } catch (error: Exception) {
        ctx.source.replyFailure(error.failureComponent("error.field.parameters"))
        0
    }
}
