package dev.nicks.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Server -> client: the identity of one player (versioned by {@code revision}). There is no client -> server payload. */
public record IdentitySyncPayload(UUID uuid, boolean cleared, String nickname, byte identityType, String skinSource,
                                  String skinHash, byte model, long revision, boolean nametag) implements CustomPayload {
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
        buf.writeByte(model);
        buf.writeLong(revision);
        buf.writeBoolean(nametag);
    }

    private static IdentitySyncPayload read(PacketByteBuf buf) {
        // bounded reads: a hostile server cannot make the client allocate huge strings
        return new IdentitySyncPayload(buf.readUuid(), buf.readBoolean(), buf.readString(64), buf.readByte(),
                buf.readString(128), buf.readString(64), buf.readByte(), buf.readLong(), buf.readBoolean());
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
