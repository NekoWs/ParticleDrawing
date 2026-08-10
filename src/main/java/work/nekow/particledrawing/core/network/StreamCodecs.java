package work.nekow.particledrawing.core.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

final class StreamCodecs {

    static final StreamCodec<FriendlyByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
        @Override
        public UUID decode(FriendlyByteBuf buf) {
            return new UUID(buf.readLong(), buf.readLong());
        }

        @Override
        public void encode(FriendlyByteBuf buf, UUID id) {
            buf.writeLong(id.getMostSignificantBits());
            buf.writeLong(id.getLeastSignificantBits());
        }
    };

    static final StreamCodec<FriendlyByteBuf, UUID> NULLABLE_UUID_CODEC = new StreamCodec<>() {
        @Override
        public UUID decode(FriendlyByteBuf buf) {
            if (buf.readBoolean()) {
                return new UUID(buf.readLong(), buf.readLong());
            }
            return null;
        }

        @Override
        public void encode(FriendlyByteBuf buf, UUID id) {
            buf.writeBoolean(id != null);
            if (id != null) {
                buf.writeLong(id.getMostSignificantBits());
                buf.writeLong(id.getLeastSignificantBits());
            }
        }
    };

    private StreamCodecs() {}
}
