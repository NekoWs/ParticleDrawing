package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.server.ServerParticleEngine
import kotlin.math.cos
import kotlin.math.sin

@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object ParticleDrawCommands {

    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val dispatcher = event.dispatcher

        dispatcher.register(
            Commands.literal("particledraw")
                .then(Commands.literal("line")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 5000))
                        .executes { ctx ->
                            spawnLine(ctx, IntegerArgumentType.getInteger(ctx, "count"))
                        }))
                .then(Commands.literal("circle")
                    .then(Commands.argument("radius", FloatArgumentType.floatArg(0.5f, 50f))
                        .then(Commands.argument("count", IntegerArgumentType.integer(4, 10000))
                            .executes { ctx ->
                                spawnCircle(
                                    ctx,
                                    FloatArgumentType.getFloat(ctx, "radius"),
                                    IntegerArgumentType.getInteger(ctx, "count")
                                )
                            })))
                .then(Commands.literal("disc")
                    .then(Commands.argument("radius", FloatArgumentType.floatArg(0.5f, 30f))
                        .then(Commands.argument("count", IntegerArgumentType.integer(4, 5000))
                            .executes { ctx ->
                                spawnDisc(
                                    ctx,
                                    FloatArgumentType.getFloat(ctx, "radius"),
                                    IntegerArgumentType.getInteger(ctx, "count")
                                )
                            })))
                .then(Commands.literal("glow")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
                        .executes { ctx ->
                            spawnGlow(ctx, IntegerArgumentType.getInteger(ctx, "count"))
                        }))
                .then(Commands.literal("stress")
                    .then(Commands.argument("count", IntegerArgumentType.integer(100, 50000))
                        .executes { ctx ->
                            stressTest(ctx, IntegerArgumentType.getInteger(ctx, "count"))
                        }))
                .then(Commands.literal("group")
                    .executes(::spawnGroupTest)
                    .then(Commands.literal("rotate")
                        .executes(::rotateGroup))
                    .then(Commands.literal("move")
                        .executes(::moveGroup))
                    .then(Commands.literal("recolor")
                        .executes(::recolorGroup)))
                .then(Commands.literal("status")
                    .executes(::showStatus))
                .then(Commands.literal("clear")
                    .executes(::clearAll))
        )
    }

    private fun spawnLine(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val start = player.position().add(player.lookAngle.scale(3.0))
        val end = start.add(player.lookAngle.scale(10.0))
        val colors = arrayOf(Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA)

        for (i in 0 until count) {
            val t = i.toDouble() / maxOf(1, count - 1).toDouble()
            pm.create()
                .style(ParticleStyle.DUST)
                .position(start.add(end.subtract(start).scale(t)))
                .color(colors[i % colors.size])
                .scale(0.4f)
                .lifetime(600)
                .spawn()
        }

        ctx.source.sendSuccess(
            { Component.literal("Spawned $count particles in a line") }, false)
        return count
    }

    private fun spawnCircle(ctx: CommandContext<CommandSourceStack>, radius: Float, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(3.0))
        Draw.circle(pm, center, radius.toDouble(), count, Draw.Axis.XZ,
            Color.CYAN, ParticleStyle.DUST, 0.4f)

        ctx.source.sendSuccess(
            { Component.literal("Spawned circle: $count particles, radius=$radius") }, false)
        return count
    }

    private fun spawnDisc(ctx: CommandContext<CommandSourceStack>, radius: Float, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(3.0))
        val layers = maxOf(1, (radius * 2).toInt())
        Draw.disc(pm, center, radius.toDouble(), count, layers, Draw.Axis.XZ,
            Color.ofHsb(0.55f, 0.8f, 1.0f), ParticleStyle.DUST, 0.3f)

        ctx.source.sendSuccess(
            { Component.literal("Spawned disc with radius=$radius") }, false)
        return count
    }

    private fun spawnGlow(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(3.0))
        for (i in 0 until count) {
            val angle = 2.0 * Math.PI * i / count
            val x = center.x + cos(angle) * 2.5
            val z = center.z + sin(angle) * 2.5
            val hue = i.toFloat() / count

            pm.create()
                .style(ParticleStyle.GLOW)
                .position(x, center.y + 1.5, z)
                .color(Color.ofHsb(hue, 1.0f, 1.0f))
                .scale(1.2f)
                .lifetime(1200)
                .glowing(true)
                .spawn()
        }

        ctx.source.sendSuccess(
            { Component.literal("Spawned $count GLOWING particles!") }, false)
        return count
    }

    private fun stressTest(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(5.0))
        val startTime = System.currentTimeMillis()

        val batchSize = 500
        var batch = 0
        while (batch < count) {
            val toSpawn = minOf(batchSize, count - batch)
            for (i in 0 until toSpawn) {
                val angle = (batch + i).toDouble() / count * 2.0 * Math.PI
                val dist = 2.0 + Math.random() * 8.0
                val x = center.x + cos(angle) * dist
                val z = center.z + sin(angle) * dist
                val y = center.y + Math.random() * 4.0
                val hue = (batch + i).toFloat() / count

                pm.create()
                    .style(ParticleStyle.DUST)
                    .position(x, y, z)
                    .color(Color.ofHsb(hue, 0.9f, 1.0f))
                    .scale(0.25f)
                    .lifetime(400)
                    .spawn()
            }
            batch += batchSize
        }

        val elapsed = System.currentTimeMillis() - startTime
        val total = pm.getEngine().particleCount()

        ctx.source.sendSuccess(
            { Component.literal("Stress test: $total particles in ${elapsed}ms") }, false)
        return total
    }

    private var testGroup: ParticleGroup? = null

    private fun spawnGroupTest(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(4.0))
        testGroup = Draw.circle(pm, center, 3.0, 36, Draw.Axis.XZ,
            Color.RED, ParticleStyle.DUST, 0.5f)

        ctx.source.sendSuccess(
            { Component.literal("Test group: ${testGroup!!.size()} particles. /particledraw group rotate|move|recolor") }, false)
        return testGroup!!.size()
    }

    private fun rotateGroup(ctx: CommandContext<CommandSourceStack>): Int {
        if (testGroup == null) {
            ctx.source.sendFailure(Component.literal("No test group! Run /particledraw group first."))
            return 0
        }
        testGroup!!.rotate(Vec3(0.0, 1.0, 0.0), Math.PI * 2, 80, EasingType.EASE_IN_OUT)
        ctx.source.sendSuccess(
            { Component.literal("Rotating group 360 deg over 80 ticks") }, false)
        return 1
    }

    private fun moveGroup(ctx: CommandContext<CommandSourceStack>): Int {
        if (testGroup == null) {
            ctx.source.sendFailure(Component.literal("No test group! Run /particledraw group first."))
            return 0
        }
        testGroup!!.move(Vec3(0.0, 2.0, 0.0), 60, EasingType.EASE_OUT_BOUNCE)
        ctx.source.sendSuccess(
            { Component.literal("Moving group up by 2 blocks (bounce easing)") }, false)
        return 1
    }

    private fun recolorGroup(ctx: CommandContext<CommandSourceStack>): Int {
        if (testGroup == null) {
            ctx.source.sendFailure(Component.literal("No test group! Run /particledraw group first."))
            return 0
        }
        testGroup!!.recolor(Color.BLUE, 40, EasingType.EASE_IN_OUT)
        ctx.source.sendSuccess(
            { Component.literal("Recoloring group to BLUE") }, false)
        return 1
    }

    private fun showStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val engine = ServerParticleEngine.getOrCreate(
            work.nekow.particledrawing.util.ParticleUtils.dimensionUUID(level))
        val serverCount = engine.particleCount()
        val serverGroups = engine.groupCount()

        var clientCount = 0
        val clientEngine = work.nekow.particledrawing.core.client.ClientParticleEngine.instance()
        if (clientEngine != null) {
            clientCount = clientEngine.activeCount()
        }

        ctx.source.sendSuccess(
            { Component.literal(
                "Server: $serverCount particles, $serverGroups groups | "
                        + "Client: $clientCount particles") }, false)
        return serverCount
    }

    private fun clearAll(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val dimId = work.nekow.particledrawing.util.ParticleUtils.dimensionUUID(level)
        val engine = ServerParticleEngine.getOrCreate(dimId)
        val cleared = engine.clearAll(level.players())
        testGroup = null

        ctx.source.sendSuccess(
            { Component.literal("Cleared $cleared particles!") }, true)
        return cleared
    }
}
