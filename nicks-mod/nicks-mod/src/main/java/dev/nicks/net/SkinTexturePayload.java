package dev.nicks.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: the PNG for a skin hash. Sent once per client per hash, always before the identity that uses it. */
public record SkinTexturePayload(String hash, byte[] png) implements CustomPayload {
    /** Hard cap on what a client will accept (a 64x64 PNG is normally 1-10 KB). */
    public static final int MAX_BYTES = 262_144;
    public static final Id<SkinTexturePayload> ID = new Id<>(Identifier.of("nicks", "skin_texture"));
    public static final PacketCodec<PacketByteBuf, SkinTexturePayload> CODEC =
            PacketCodec.of(SkinTexturePayload::write, SkinTexturePayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeString(hash);
        buf.writeByteArray(png);
    }

    private static SkinTexturePayload read(PacketByteBuf buf) {
        return new SkinTexturePayload(buf.readString(64), buf.readByteArray(MAX_BYTES));
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
