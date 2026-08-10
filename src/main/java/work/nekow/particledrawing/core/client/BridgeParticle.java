package work.nekow.particledrawing.core.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import org.jspecify.annotations.NonNull;
import work.nekow.particledrawing.api.Color;
import work.nekow.particledrawing.api.ParticleStyle;

import java.util.UUID;

@SuppressWarnings("unused")
public final class BridgeParticle extends SingleQuadParticle {

    private final UUID id;
    private final boolean isGlowing;

    BridgeParticle(UUID id, ParticleStyle style, ClientLevel level,
                   double x, double y, double z, Color color, float scale,
                   boolean glowing) {
        super(level, x, y, z, getSpriteForStyle(style));
        this.id = id;
        this.isGlowing = glowing;
        this.xo = x;
        this.yo = y;
        this.zo = z;

        this.setColor(color.r(), color.g(), color.b());
        this.alpha = glowing ? 0f : color.a(); // invisible when glowing
        this.quadSize = scale;
        this.lifetime = Integer.MAX_VALUE;
        this.gravity = 0;
        this.hasPhysics = false;
    }

    public UUID particleId() { return id; }
    public boolean isGlowing() { return isGlowing; }

    public void syncPosition(double x, double y, double z) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void syncColor(float r, float g, float b, float a) {
        this.rCol = r;
        this.gCol = g;
        this.bCol = b;
        this.alpha = isGlowing ? 0f : a;
    }

    public void syncScale(float scale) {
        this.quadSize = scale;
    }

    @Override
    public void tick() {
        this.age++;
        if (this.age >= this.lifetime) {
            this.remove();
        }
    }

    @Override
    protected @NonNull Layer getLayer() {
        if (this.alpha < 1.0f || this.sprite.transparency().hasTranslucent()) {
            return Layer.TRANSLUCENT;
        }
        return Layer.OPAQUE;
    }

    @Override
    protected int getLightCoords(float partialTick) {
        if (isGlowing) {
            return 0x00F000F0;
        }
        return super.getLightCoords(partialTick);
    }

    private static TextureAtlasSprite getSpriteForStyle(ParticleStyle style) {
        var atlas = net.minecraft.client.Minecraft.getInstance()
            .getAtlasManager()
            .getAtlasOrThrow(AtlasIds.PARTICLES);
        return atlas.getSprite(style.spriteLocation());
    }
}
