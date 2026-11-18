package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import work.nekow.particledrawing.api.ForceFieldKind
import work.nekow.particledrawing.api.ParticleForceField
import work.nekow.particledrawing.api.ParticleForceFields
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.i18n.component
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
            root.then(Commands.literal(kind.name.lowercase()).then(Commands.argument("id", StringArgumentType.string())
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.05, 128.0)).then(strength))))
        }
        root.then(Commands.literal("anchor").then(Commands.argument("id", StringArgumentType.string())
            .then(Commands.literal("fixed").then(Commands.argument("position", Vec3Argument.vec3()).executes { ctx -> safe(ctx) {
                ParticleForceFields.anchor(ctx.source.level, id(ctx), RecipeAnchor(Vec3Argument.getVec3(ctx, "position")))
            } }))
            .then(Commands.literal("entity").then(Commands.argument("entity", EntityArgument.entity()).executes { ctx -> safe(ctx) {
                val entity = EntityArgument.getEntity(ctx, "entity")
                ParticleForceFields.anchor(ctx.source.level, id(ctx), RecipeAnchor(entity.position(), entity.uuid, entity.id, local = true))
            } }))))
        root.then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.string()).executes { ctx -> safe(ctx) {
            requireLocalized(ParticleForceFields.remove(ctx.source.level, id(ctx))) { translation("error.field.not_found", id(ctx)) }
        } }))
        root.then(Commands.literal("list").executes { ctx ->
            val ids = ParticleForceFields.ids(ctx.source.level)
            ctx.source.sendSuccess({ translation("command.field.list", ids.joinToString(", ")).component() }, false)
            ids.size
        })
        return root
    }

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

    private fun id(ctx: CommandContext<CommandSourceStack>) = ResourceId.parse(word(ctx, "id"))
    private fun word(ctx: CommandContext<CommandSourceStack>, key: String) = StringArgumentType.getString(ctx, key)

    private inline fun safe(ctx: CommandContext<CommandSourceStack>, action: () -> Unit): Int = try {
        action()
        ctx.source.sendSuccess({ translation("command.field.updated", id(ctx)).component() }, false)
        1
    } catch (error: Exception) {
        ctx.source.sendFailure(error.failureComponent("error.field.parameters"))
        0
    }
}
