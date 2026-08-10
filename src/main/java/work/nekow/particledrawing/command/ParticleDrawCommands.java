package work.nekow.particledrawing.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import work.nekow.particledrawing.ParticleDrawing;
import work.nekow.particledrawing.api.*;
import work.nekow.particledrawing.core.easing.EasingType;
import work.nekow.particledrawing.core.server.ServerParticleEngine;

@EventBusSubscriber(modid = ParticleDrawing.MODID)
public final class ParticleDrawCommands {

    private ParticleDrawCommands() {}

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("particledraw")
                .then(Commands.literal("line")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 5000))
                        .executes(ctx -> spawnLine(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("circle")
                    .then(Commands.argument("radius", FloatArgumentType.floatArg(0.5f, 50f))
                        .then(Commands.argument("count", IntegerArgumentType.integer(4, 10000))
                            .executes(ctx -> spawnCircle(
                                ctx,
                                FloatArgumentType.getFloat(ctx, "radius"),
                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("disc")
                    .then(Commands.argument("radius", FloatArgumentType.floatArg(0.5f, 30f))
                        .then(Commands.argument("count", IntegerArgumentType.integer(4, 5000))
                            .executes(ctx -> spawnDisc(
                                ctx,
                                FloatArgumentType.getFloat(ctx, "radius"),
                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("glow")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
                        .executes(ctx -> spawnGlow(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("stress")
                    .then(Commands.argument("count", IntegerArgumentType.integer(100, 50000))
                        .executes(ctx -> stressTest(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("group")
                    .executes(ParticleDrawCommands::spawnGroupTest)
                    .then(Commands.literal("rotate")
                        .executes(ParticleDrawCommands::rotateGroup))
                    .then(Commands.literal("move")
                        .executes(ParticleDrawCommands::moveGroup))
                    .then(Commands.literal("recolor")
                        .executes(ParticleDrawCommands::recolorGroup)))
                .then(Commands.literal("status")
                    .executes(ParticleDrawCommands::showStatus))
                .then(Commands.literal("clear")
                    .executes(ParticleDrawCommands::clearAll))
        );
    }

    private static int spawnLine(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        ParticleManager pm = ParticleManager.of(level);

        Vec3 start = player.position().add(player.getLookAngle().scale(3));
        Vec3 end = start.add(player.getLookAngle().scale(10));
        Color[] colors = {Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA};

        for (int i = 0; i < count; i++) {
            double t = (double) i / Math.max(1, count - 1);
            pm.create()
                .style(ParticleStyle.DUST)
                .position(start.add(end.subtract(start).scale(t)))
                .color(colors[i % colors.length])
                .scale(0.4f)
                .lifetime(600)
                .spawn();
        }

        ctx.getSource().sendSuccess(
            () -> Component.literal("Spawned " + count + " particles in a line"), false);
        return count;
    }

    private static int spawnCircle(CommandContext<CommandSourceStack> ctx, float radius, int count) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        ParticleManager pm = ParticleManager.of(level);

        Vec3 center = player.position().add(player.getLookAngle().scale(3));
        Draw.circle(pm, center, radius, count, Draw.Axis.XZ,
            Color.CYAN, ParticleStyle.DUST, 0.4f);

        ctx.getSource().sendSuccess(
            () -> Component.literal("Spawned circle: " + count + " particles, radius=" + radius), false);
        return count;
    }

    private static int spawnDisc(CommandContext<CommandSourceStack> ctx, float radius, int count) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        ParticleManager pm = ParticleManager.of(level);

        Vec3 center = player.position().add(player.getLookAngle().scale(3));
        int layers = Math.max(1, (int)(radius * 2));
        Draw.disc(pm, center, radius, count, layers, Draw.Axis.XZ,
            Color.ofHsb(0.55f, 0.8f, 1.0f), ParticleStyle.DUST, 0.3f);

        ctx.getSource().sendSuccess(
            () -> Component.literal("Spawned disc with radius=" + radius), false);
        return count;
    }

    private static int spawnGlow(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        ParticleManager pm = ParticleManager.of(level);

        Vec3 center = player.position().add(player.getLookAngle().scale(3));
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            double x = center.x + Math.cos(angle) * 2.5;
            double z = center.z + Math.sin(angle) * 2.5;
            float hue = (float) i / count;

            pm.create()
                .style(ParticleStyle.GLOW)
                .position(x, center.y + 1.5, z)
                .color(Color.ofHsb(hue, 1.0f, 1.0f))
                .scale(1.2f)
                .lifetime(1200)
                .glowing(true)
                .spawn();
        }

        ctx.getSource().sendSuccess(
            () -> Component.literal("Spawned " + count + " GLOWING particles!"), false);
        return count;
    }

    private static int stressTest(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        ParticleManager pm = ParticleManager.of(level);

        Vec3 center = player.position().add(player.getLookAngle().scale(5));
        long startTime = System.currentTimeMillis();

        int batchSize = 500;
        for (int batch = 0; batch < count; batch += batchSize) {
            int toSpawn = Math.min(batchSize, count - batch);
            for (int i = 0; i < toSpawn; i++) {
                double angle = (double) (batch + i) / count * 2.0 * Math.PI;
                double dist = 2.0 + Math.random() * 8.0;
                double x = center.x + Math.cos(angle) * dist;
                double z = center.z + Math.sin(angle) * dist;
                double y = center.y + Math.random() * 4.0;
                float hue = (float) (batch + i) / count;

                pm.create()
                    .style(ParticleStyle.DUST)
                    .position(x, y, z)
                    .color(Color.ofHsb(hue, 0.9f, 1.0f))
                    .scale(0.25f)
                    .lifetime(400)
                    .spawn();
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        int total = pm.getEngine().particleCount();

        ctx.getSource().sendSuccess(
            () -> Component.literal("Stress test: " + total + " particles in " + elapsed + "ms"), false);
        return total;
    }

    private static ParticleGroup testGroup = null;

    private static int spawnGroupTest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        ParticleManager pm = ParticleManager.of(level);

        Vec3 center = player.position().add(player.getLookAngle().scale(4));
        testGroup = Draw.circle(pm, center, 3.0, 36, Draw.Axis.XZ,
            Color.RED, ParticleStyle.DUST, 0.5f);

        ctx.getSource().sendSuccess(
            () -> Component.literal("Test group: " + testGroup.size() + " particles. /particledraw group rotate|move|recolor"), false);
        return testGroup.size();
    }

    private static int rotateGroup(CommandContext<CommandSourceStack> ctx) {
        if (testGroup == null) {
            ctx.getSource().sendFailure(Component.literal("No test group! Run /particledraw group first."));
            return 0;
        }
        testGroup.rotate(new Vec3(0, 1, 0), Math.PI * 2, 80, EasingType.EASE_IN_OUT);
        ctx.getSource().sendSuccess(
            () -> Component.literal("Rotating group 360 deg over 80 ticks"), false);
        return 1;
    }

    private static int moveGroup(CommandContext<CommandSourceStack> ctx) {
        if (testGroup == null) {
            ctx.getSource().sendFailure(Component.literal("No test group! Run /particledraw group first."));
            return 0;
        }
        testGroup.move(new Vec3(0, 2, 0), 60, EasingType.EASE_OUT_BOUNCE);
        ctx.getSource().sendSuccess(
            () -> Component.literal("Moving group up by 2 blocks (bounce easing)"), false);
        return 1;
    }

    private static int recolorGroup(CommandContext<CommandSourceStack> ctx) {
        if (testGroup == null) {
            ctx.getSource().sendFailure(Component.literal("No test group! Run /particledraw group first."));
            return 0;
        }
        testGroup.recolor(Color.BLUE, 40, EasingType.EASE_IN_OUT);
        ctx.getSource().sendSuccess(
            () -> Component.literal("Recoloring group to BLUE"), false);
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        ServerParticleEngine engine = ServerParticleEngine.getOrCreate(
            work.nekow.particledrawing.util.ParticleUtils.dimensionUUID(level));
        int serverCount = engine.particleCount();
        int serverGroups = engine.groupCount();

        int clientCount = 0;
        var clientEngine = work.nekow.particledrawing.core.client.ClientParticleEngine.instance();
        if (clientEngine != null) {
            clientCount = clientEngine.activeCount();
        }

        final int fc = clientCount;
        ctx.getSource().sendSuccess(
            () -> Component.literal(
                "Server: " + serverCount + " particles, " + serverGroups + " groups | "
                + "Client: " + fc + " particles"), false);
        return serverCount;
    }

    private static int clearAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        var dimId = work.nekow.particledrawing.util.ParticleUtils.dimensionUUID(level);
        var engine = ServerParticleEngine.getOrCreate(dimId);
        int cleared = engine.clearAll(level.players());
        testGroup = null;

        ctx.getSource().sendSuccess(
            () -> Component.literal("Cleared " + cleared + " particles!"), true);
        return cleared;
    }
}
