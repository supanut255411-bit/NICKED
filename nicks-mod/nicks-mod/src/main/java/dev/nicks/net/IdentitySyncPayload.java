package dev.nicks.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * Server -> client: the identity of one player (versioned by {@code revision}; revision -1 means "forget this player").
 * The appearance is skin + cape from one source: {@code skinHash} (custom skin) or {@code defaultSource} (use that
 * account's built-in default skin), plus an optional {@code capeHash}. There is no client -> server payload.
 */
public record IdentitySyncPayload(UUID uuid, boolean cleared, String nickname, byte identityType, String skinSource,
                                  String skinHash, String capeHash, UUID defaultSource, byte model, long revision,
                                  boolean nametag) implements CustomPayload {
    public static final UUID NONE = new UUID(0L, 0L);
    public static final Id<IdentitySyncPayload> ID = new Id<>(Identifier.of("nicks", "identity_sync"));
    public static final PacketCodec<PacketByteBuf, IdentitySyncPayload> CODEC =
            PacketCodec.of(IdentitySyncPayload::write, IdentitySyncPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeUuid(uuid);
        buf.writeBoolean(cleared);
        buf.writeString(nickname);
        buf.writeByte(identityType);
        buf.writeString(skinSource);
        buf.writeString(skinHash);
        buf.writeString(capeHash);
        buf.writeUuid(defaultSource);
        buf.writeByte(model);
        buf.writeLong(revision);
        buf.writeBoolean(nametag);
    }

    private static IdentitySyncPayload read(PacketByteBuf buf) {
        // bounded reads: a hostile server cannot make the client allocate huge strings
        return new IdentitySyncPayload(buf.readUuid(), buf.readBoolean(), buf.readString(64), buf.readByte(),
                buf.readString(128), buf.readString(64), buf.readString(64), buf.readUuid(), buf.readByte(),
                buf.readLong(), buf.readBoolean());
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
