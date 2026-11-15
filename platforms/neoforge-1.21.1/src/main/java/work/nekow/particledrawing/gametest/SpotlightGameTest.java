package work.nekow.particledrawing.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import work.nekow.particledrawing.api.ParticleHandle;
import work.nekow.particledrawing.api.ParticleManager;
import work.nekow.particledrawing.core.easing.EasingType;
import work.nekow.particledrawing.light.LightShape;

import java.util.Objects;

@GameTestHolder("particledrawing")
@PrefixGameTestTemplate(false)
public final class SpotlightGameTest {
    @GameTest(template = "empty")
    public static void spotlightLifecycle(GameTestHelper helper) {
        Vec3 position = Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO));
        ParticleHandle lamp = Objects.requireNonNull(ParticleManager.of(helper.getLevel()).create()
            .position(position).spotlight(12, 35).lightOnly(true).spawn(), "Spotlight did not spawn");
        try {
            helper.assertValueEqual(lamp.lightShape(), LightShape.spot(12, 35), "Spawned spotlight shape");
            lamp.lightDirection(70, -45, 20, EasingType.EASE_IN_OUT)
                .lightFocus(24, 20, EasingType.EASE_OUT);
            LightShape target = LightShape.spot(12, 24).withDirection(70, -45);
            helper.assertValueEqual(lamp.lightShape(), target, "Spotlight target after turn and focus");
        } finally {
            lamp.remove();
        }
        helper.assertTrue(lamp.data() == null, "Removed spotlight must leave the server engine");
        helper.succeed();
    }
}
