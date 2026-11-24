package work.nekow.particledrawing.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import work.nekow.particledrawing.command.help.HelpBook
import work.nekow.particledrawing.command.v2.ParticleCommandsV2
import work.nekow.particledrawing.platform.PlatformCompat

internal object ParticleDrawParticleCommands {
    fun tree(): LiteralArgumentBuilder<CommandSourceStack> = ParticleCommandsV2.register(
        Commands.literal("particle")
            .requires(PlatformCompat.gamemastersOnly())
            .executes { HelpBook.topic(it.source, "particle") }
            .then(Commands.literal("help").executes { HelpBook.topic(it.source, "particle") })
            .then(ParticleFieldCommands.tree()),
    )
}
