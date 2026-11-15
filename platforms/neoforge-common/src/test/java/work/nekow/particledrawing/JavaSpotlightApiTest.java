package work.nekow.particledrawing;

import org.junit.Test;
import work.nekow.particledrawing.api.Ease;
import work.nekow.particledrawing.api.Light;
import work.nekow.particledrawing.api.ParticleAppearance;
import work.nekow.particledrawing.api.ParticleHandle;
import work.nekow.particledrawing.api.ParticleManager;
import work.nekow.particledrawing.api.ParticleSpawnSpec;
import work.nekow.particledrawing.api.ParticleVisual;
import work.nekow.particledrawing.api.Time;
import work.nekow.particledrawing.core.easing.EasingType;
import work.nekow.particledrawing.light.LightFalloff;
import work.nekow.particledrawing.light.LightShape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JavaSpotlightApiTest {
    @Test
    public void createsAndReusesASpotlightWithoutKotlinDefaults() {
        LightShape beam = LightShape.spot(12, 35)
            .withDirection(25, -35)
            .withFalloff(LightFalloff.SMOOTH)
            .withFocus(24);
        ParticleVisual visual = ParticleAppearance.builder()
            .light(Light.only(15).withShape(beam))
            .build();
        ParticleSpawnSpec spec = new ParticleSpawnSpec().lightShape(beam).lightOnly(true);
        assertEquals(beam, visual.getLightShape());
        assertEquals(beam, spec.getVisual().getLightShape());
        assertTrue(visual.getLightOnly());
    }

    // 由 javac 检查文档里的快捷调用可从 Java 使用。
    static ParticleHandle createAndUpdate(ParticleManager manager) {
        ParticleHandle lamp = manager.create().position(1, 2, 3)
            .spotlight(12, 35).lightDirection(25, -35).lightOnly(true).spawn();
        if (lamp != null) {
            lamp.lightDirection(70, -45, 20, EasingType.EASE_IN_OUT)
                .lightFocus(24, 20, EasingType.EASE_OUT).lightIntensity(0.4f, 8);
            lamp.lightDirection(80, -35, Time.ticks(8), Ease.IN)
                .lightFocus(18, Time.ticks(8), Ease.OUT);
            lamp.lightFocus(24, 14, 2).lightFocus(18, 10, 1.5, 20, EasingType.LINEAR);
        }
        return lamp;
    }
}
