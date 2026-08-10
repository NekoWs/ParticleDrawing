package work.nekow.particledrawing.core.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import work.nekow.particledrawing.core.easing.EasingType;

import java.util.UUID;

@org.jspecify.annotations.NullMarked
public record ParticleGroupTransformPayload(
    UUID groupId,
    int transformType,
    double dx, double dy, double dz,
    double ax, double ay, double az,
    double radians,
    float r, float g, float b, float a,
    float targetScale,
    double px, double py, double pz,
    int durationTicks,
    double e0, double e1, double e2, double e3, double e4
) implements CustomPacketPayload {

    public static final Type<ParticleGroupTransformPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("particledrawing", "group_transform")
    );

    public static final StreamCodec<FriendlyByteBuf, ParticleGroupTransformPayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public ParticleGroupTransformPayload decode(FriendlyByteBuf buf) {
                UUID gid = StreamCodecs.UUID_CODEC.decode(buf);
                int tt = ByteBufCodecs.VAR_INT.decode(buf);
                double dx = buf.readDouble();
                double dy = buf.readDouble();
                double dz = buf.readDouble();
                double ax = buf.readDouble();
                double ay = buf.readDouble();
                double az = buf.readDouble();
                double rad = buf.readDouble();
                float r = buf.readFloat();
                float g = buf.readFloat();
                float b = buf.readFloat();
                float a = buf.readFloat();
                float ts = buf.readFloat();
                double px = buf.readDouble();
                double py = buf.readDouble();
                double pz = buf.readDouble();
                int dur = ByteBufCodecs.VAR_INT.decode(buf);
                double e0 = buf.readDouble();
                double e1 = buf.readDouble();
                double e2 = buf.readDouble();
                double e3 = buf.readDouble();
                double e4 = buf.readDouble();
                return new ParticleGroupTransformPayload(
                    gid, tt, dx, dy, dz, ax, ay, az, rad, r, g, b, a, ts, px, py, pz, dur, e0, e1, e2, e3, e4);
            }

            @Override
            public void encode(FriendlyByteBuf buf, ParticleGroupTransformPayload p) {
                StreamCodecs.UUID_CODEC.encode(buf, p.groupId);
                ByteBufCodecs.VAR_INT.encode(buf, p.transformType);
                buf.writeDouble(p.dx); buf.writeDouble(p.dy); buf.writeDouble(p.dz);
                buf.writeDouble(p.ax); buf.writeDouble(p.ay); buf.writeDouble(p.az);
                buf.writeDouble(p.radians);
                buf.writeFloat(p.r); buf.writeFloat(p.g); buf.writeFloat(p.b); buf.writeFloat(p.a);
                buf.writeFloat(p.targetScale);
                buf.writeDouble(p.px); buf.writeDouble(p.py); buf.writeDouble(p.pz);
                ByteBufCodecs.VAR_INT.encode(buf, p.durationTicks);
                buf.writeDouble(p.e0); buf.writeDouble(p.e1); buf.writeDouble(p.e2);
                buf.writeDouble(p.e3); buf.writeDouble(p.e4);
            }
        };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public EasingType easingType() {
        return EasingType.deserialize(new double[]{e0, e1, e2, e3, e4});
    }

    public static ParticleGroupTransformPayload translate(UUID groupId,
                                                           double dx, double dy, double dz,
                                                           double px, double py, double pz,
                                                           int durationTicks, EasingType easing) {
        double[] ser = easing.serialize();
        return new ParticleGroupTransformPayload(groupId, 0,
            dx, dy, dz, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            px, py, pz, durationTicks,
            ser[0], ser[1], ser[2], ser[3], ser[4]);
    }

    public static ParticleGroupTransformPayload rotate(UUID groupId,
                                                        double ax, double ay, double az,
                                                        double radians,
                                                        double px, double py, double pz,
                                                        int durationTicks, EasingType easing) {
        double[] ser = easing.serialize();
        return new ParticleGroupTransformPayload(groupId, 1,
            0, 0, 0, ax, ay, az, radians, 0, 0, 0, 0, 0,
            px, py, pz, durationTicks,
            ser[0], ser[1], ser[2], ser[3], ser[4]);
    }

    public static ParticleGroupTransformPayload recolor(UUID groupId,
                                                         float r, float g, float b, float a,
                                                         int durationTicks, EasingType easing) {
        double[] ser = easing.serialize();
        return new ParticleGroupTransformPayload(groupId, 2,
            0, 0, 0, 0, 0, 0, 0, r, g, b, a, 0,
            0, 0, 0, durationTicks,
            ser[0], ser[1], ser[2], ser[3], ser[4]);
    }

    public static ParticleGroupTransformPayload scale(UUID groupId, float targetScale,
                                                       double px, double py, double pz,
                                                       int durationTicks, EasingType easing) {
        double[] ser = easing.serialize();
        return new ParticleGroupTransformPayload(groupId, 3,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, targetScale,
            px, py, pz, durationTicks,
            ser[0], ser[1], ser[2], ser[3], ser[4]);
    }
}
