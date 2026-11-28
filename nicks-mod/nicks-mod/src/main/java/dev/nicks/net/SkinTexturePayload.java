package dev.nicks.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> client: the PNG for a skin hash ({@code kind} 0) or cape hash ({@code kind} 1). Sent when the client does not
 * currently hold that texture, always before the identity that references it.
 */
public record SkinTexturePayload(byte kind, String hash, byte[] png) implements CustomPayload {
    public static final byte KIND_SKIN = 0, KIND_CAPE = 1;
    /** Hard cap on what a client will accept (a 64x64 skin or 64x32 cape PNG is normally 1-10 KB). */
    public static final int MAX_BYTES = 262_144;
    public static final Id<SkinTexturePayload> ID = new Id<>(Identifier.of("nicks", "skin_texture"));
    public static final PacketCodec<PacketByteBuf, SkinTexturePayload> CODEC =
            PacketCodec.of(SkinTexturePayload::write, SkinTexturePayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeByte(kind);
        buf.writeString(hash);
        buf.writeByteArray(png);
    }

    private static SkinTexturePayload read(PacketByteBuf buf) {
        return new SkinTexturePayload(buf.readByte(), buf.readString(64), buf.readByteArray(MAX_BYTES));
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
