package work.nekow.particledrawing.core.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import work.nekow.particledrawing.core.easing.EasingType;

import java.util.UUID;

@org.jspecify.annotations.NullMarked
public record ParticleUpdatePayload(
    UUID particleId,
    double x, double y, double z,
    float r, float g, float b, float a,
    float scale,
    int durationTicks,
    boolean hasPosition,
    boolean hasColor,
    boolean hasScale,
    double e0, double e1, double e2, double e3, double e4
) implements CustomPacketPayload {

    public static final Type<ParticleUpdatePayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("particledrawing", "particle_update")
    );

    public static final StreamCodec<FriendlyByteBuf, ParticleUpdatePayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public ParticleUpdatePayload decode(FriendlyByteBuf buf) {
                UUID id = StreamCodecs.UUID_CODEC.decode(buf);
                double x = buf.readDouble();
                double y = buf.readDouble();
                double z = buf.readDouble();
                float r = buf.readFloat();
                float g = buf.readFloat();
                float b = buf.readFloat();
                float a = buf.readFloat();
                float scale = buf.readFloat();
                int dur = ByteBufCodecs.VAR_INT.decode(buf);
                boolean hp = buf.readBoolean();
                boolean hc = buf.readBoolean();
                boolean hs = buf.readBoolean();
                double e0 = buf.readDouble();
                double e1 = buf.readDouble();
                double e2 = buf.readDouble();
                double e3 = buf.readDouble();
                double e4 = buf.readDouble();
                return new ParticleUpdatePayload(id, x, y, z, r, g, b, a, scale, dur, hp, hc, hs, e0, e1, e2, e3, e4);
            }

            @Override
            public void encode(FriendlyByteBuf buf, ParticleUpdatePayload p) {
                StreamCodecs.UUID_CODEC.encode(buf, p.particleId);
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeFloat(p.r);
                buf.writeFloat(p.g);
                buf.writeFloat(p.b);
                buf.writeFloat(p.a);
                buf.writeFloat(p.scale);
                ByteBufCodecs.VAR_INT.encode(buf, p.durationTicks);
                buf.writeBoolean(p.hasPosition);
                buf.writeBoolean(p.hasColor);
                buf.writeBoolean(p.hasScale);
                buf.writeDouble(p.e0);
                buf.writeDouble(p.e1);
                buf.writeDouble(p.e2);
                buf.writeDouble(p.e3);
                buf.writeDouble(p.e4);
            }
        };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public EasingType easingType() {
        return EasingType.deserialize(new double[]{e0, e1, e2, e3, e4});
    }

    public static ParticleUpdatePayload positionOnly(UUID id, double x, double y, double z,
                                                      int durationTicks, EasingType easing) {
        double[] ser = easing.serialize();
        return new ParticleUpdatePayload(id, x, y, z, 0, 0, 0, 0, 0, durationTicks,
            true, false, false, ser[0], ser[1], ser[2], ser[3], ser[4]);
    }

    public static ParticleUpdatePayload colorOnly(UUID id, float r, float g, float b, float a,
                                                   int durationTicks, EasingType easing) {
        double[] ser = easing.serialize();
        return new ParticleUpdatePayload(id, 0, 0, 0, r, g, b, a, 0, durationTicks,
            false, true, false, ser[0], ser[1], ser[2], ser[3], ser[4]);
    }

    public static ParticleUpdatePayload scaleOnly(UUID id, float scale,
                                                   int durationTicks, EasingType easing) {
        double[] ser = easing.serialize();
        return new ParticleUpdatePayload(id, 0, 0, 0, 0, 0, 0, 0, scale, durationTicks,
            false, false, true, ser[0], ser[1], ser[2], ser[3], ser[4]);
    }

    public static ParticleUpdatePayload full(UUID id,
                                              double x, double y, double z,
                                              float r, float g, float b, float a,
                                              float scale, int durationTicks, EasingType easing) {
        double[] ser = easing.serialize();
        return new ParticleUpdatePayload(id, x, y, z, r, g, b, a, scale, durationTicks,
            true, true, true, ser[0], ser[1], ser[2], ser[3], ser[4]);
    }
}
