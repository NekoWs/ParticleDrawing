package work.nekow.particledrawing;

import net.minecraft.world.entity.Entity;
import work.nekow.particledrawing.api.Acceleration;
import work.nekow.particledrawing.api.Color;
import work.nekow.particledrawing.api.Draw;
import work.nekow.particledrawing.api.Ease;
import work.nekow.particledrawing.api.ExitSpec;
import work.nekow.particledrawing.api.Exits;
import work.nekow.particledrawing.api.ExpressionTextBuilder;
import work.nekow.particledrawing.api.Expressions;
import work.nekow.particledrawing.api.Length;
import work.nekow.particledrawing.api.LifeCurves;
import work.nekow.particledrawing.api.Lifetime;
import work.nekow.particledrawing.api.Light;
import work.nekow.particledrawing.api.ParticleAppearance;
import work.nekow.particledrawing.api.ParticleBatch;
import work.nekow.particledrawing.api.ParticleGroup;
import work.nekow.particledrawing.api.ParticleLifeCurve;
import work.nekow.particledrawing.api.ParticleManager;
import work.nekow.particledrawing.api.ParticleSpawnSpec;
import work.nekow.particledrawing.api.ParticleStyle;
import work.nekow.particledrawing.api.ParticleVisual;
import work.nekow.particledrawing.api.PolygonSpec;
import work.nekow.particledrawing.api.Scalars;
import work.nekow.particledrawing.api.Shape;
import work.nekow.particledrawing.api.Shapes;
import work.nekow.particledrawing.api.SpawnResult;
import work.nekow.particledrawing.api.Time;
import work.nekow.particledrawing.api.Velocity;

import java.util.List;

/**
 * 编译门禁：这段 Java 不执行，只验证公开 Builder / 静态工厂能被 javac 解析。
 */
@SuppressWarnings("unused")
public final class ReadableApiJavaCompile {

    static void example(
            ParticleManager manager,
            ParticleGroup group,
            ParticleBatch batch,
            Entity holder
    ) {
        ParticleVisual visual = ParticleAppearance.builder()
                .style(ParticleStyle.SQUARE)
                .worldSize(Length.mcPixels(1.5), Length.mcPixels(1.0))
                .fullBright(true)
                .light(Light.none())
                .build();

        ParticleLifeCurve curve = LifeCurves.sizeEnvelope(
                Time.ticks(12), Time.ticks(2), Time.ticks(3));

        ParticleSpawnSpec spec = new ParticleSpawnSpec()
                .lifetime(Lifetime.ticks(20))
                .velocity(Velocity.blocksPerTick(0.0, 0.0, 0.0))
                .acceleration(Acceleration.blocksPerTickSquared(0.0, 0.0, 0.0), 4)
                .visual(visual);
        List<SpawnResult> results = manager.spawnAllDetailed(List.of(spec));

        PolygonSpec polygon = Shapes.builder();
        polygon.setSides(6);
        polygon.setRadius(Length.blocks(1.0));
        polygon.setAppearance(visual);
        polygon.part("teeth", (index, edge, edgeT) -> edge == 0, Color.ORANGE, null);
        Shape shape = Draw.polygon(manager, polygon);
        shape.part("teeth").resize(0.5f, 4, Ease.OUT);

        ExpressionTextBuilder expression = Expressions.builder(Time.ticks(5))
                .positionWorld(Scalars.of(0.0), Scalars.of(1.0), Scalars.of(2.0))
                .scale(Scalars.pow(Scalars.clamp01(Scalars.of(0.5)), 0.75))
                .color(Scalars.of(0.5), Scalars.of(0.25), Scalars.of(0.1), Scalars.of(1.0));
        expression.apply(group);

        ExitSpec exit = Exits.batch(batch);
        exit.getParticleSize().to(0.0, Time.ticks(8), Ease.LINEAR);
        exit.preserveMotion();
        exit.removeWhenComplete();
        Exits.apply(exit);

        group.fadeOut(Time.ticks(8));
    }
}
