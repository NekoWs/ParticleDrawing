package work.nekow.particledrawing.core.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.*;

public record ParticleDestroyPayload(
    UUID[] particleIds,
    UUID groupId
) implements CustomPacketPayload {

    public static final Type<ParticleDestroyPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("particledrawing", "particle_destroy")
    );

    public static final StreamCodec<FriendlyByteBuf, ParticleDestroyPayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public @NonNull ParticleDestroyPayload decode(FriendlyByteBuf buf) {
                int count = buf.readVarInt();
                UUID[] ids = new UUID[count];
                for (int i = 0; i < count; i++) {
                    ids[i] = new UUID(buf.readLong(), buf.readLong());
                }
                boolean hasGroup = buf.readBoolean();
                UUID groupId = hasGroup ? new UUID(buf.readLong(), buf.readLong()) : null;
                return new ParticleDestroyPayload(ids, groupId);
            }

            @Override
            public void encode(FriendlyByteBuf buf, ParticleDestroyPayload payload) {
                buf.writeVarInt(payload.particleIds.length);
                for (UUID id : payload.particleIds) {
                    buf.writeLong(id.getMostSignificantBits());
                    buf.writeLong(id.getLeastSignificantBits());
                }
                buf.writeBoolean(payload.groupId != null);
                if (payload.groupId != null) {
                    buf.writeLong(payload.groupId.getMostSignificantBits());
                    buf.writeLong(payload.groupId.getLeastSignificantBits());
                }
            }
        };

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static ParticleDestroyPayload single(UUID particleId) {
        return new ParticleDestroyPayload(new UUID[]{particleId}, null);
    }

    public static ParticleDestroyPayload group(UUID groupId, Collection<UUID> memberIds) {
        return new ParticleDestroyPayload(memberIds.toArray(new UUID[0]), groupId);
    }

    public static ParticleDestroyPayload batch(Collection<UUID> ids) {
        return new ParticleDestroyPayload(ids.toArray(new UUID[0]), null);
    }
}
