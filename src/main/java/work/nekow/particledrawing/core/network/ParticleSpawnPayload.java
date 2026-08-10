package work.nekow.particledrawing.core.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import work.nekow.particledrawing.api.Color;
import work.nekow.particledrawing.api.ParticleStyle;

import java.util.UUID;

@SuppressWarnings("unused")
public record ParticleSpawnPayload(
    UUID particleId,
    ParticleStyle style,
    double x, double y, double z,
    float r, float g, float b, float a,
    float scale,
    int lifetime,
    UUID groupId,
    boolean glowing
) implements CustomPacketPayload {

    public static final Type<ParticleSpawnPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("particledrawing", "particle_spawn")
    );

    public static final StreamCodec<FriendlyByteBuf, ParticleSpawnPayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public ParticleSpawnPayload decode(FriendlyByteBuf buf) {
                UUID pid = StreamCodecs.UUID_CODEC.decode(buf);
                ParticleStyle sty = ParticleStyle.values()[buf.readVarInt()];
                double x = buf.readDouble();
                double y = buf.readDouble();
                double z = buf.readDouble();
                float r = buf.readFloat();
                float g = buf.readFloat();
                float b = buf.readFloat();
                float a = buf.readFloat();
                float scale = buf.readFloat();
                int lifetime = buf.readVarInt();
                UUID gid = StreamCodecs.NULLABLE_UUID_CODEC.decode(buf);
                boolean glw = buf.readBoolean();
                return new ParticleSpawnPayload(pid, sty, x, y, z, r, g, b, a, scale, lifetime, gid, glw);
            }

            @Override
            public void encode(FriendlyByteBuf buf, ParticleSpawnPayload p) {
                StreamCodecs.UUID_CODEC.encode(buf, p.particleId);
                buf.writeVarInt(p.style.ordinal());
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeFloat(p.r);
                buf.writeFloat(p.g);
                buf.writeFloat(p.b);
                buf.writeFloat(p.a);
                buf.writeFloat(p.scale);
                buf.writeVarInt(p.lifetime);
                StreamCodecs.NULLABLE_UUID_CODEC.encode(buf, p.groupId);
                buf.writeBoolean(p.glowing);
            }
        };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public Vec3 position() {
        return new Vec3(x, y, z);
    }

    public Color color() {
        return Color.of(r, g, b, a);
    }
}
