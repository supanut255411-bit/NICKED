package dev.nicks.net;

import dev.nicks.NicksCore;
import dev.nicks.core.NicksLog;
import dev.nicks.core.PlayerIdentity;
import dev.nicks.core.SkinData;
import dev.nicks.core.SkinPipeline;
import dev.nicks.core.TextureLedger;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * Server-authoritative identity synchronisation (skin + cape together). Clients without the mod are skipped: they still
 * get every name change through vanilla packets, but not custom skins, capes or nametags.
 */
public final class IdentityNetwork {
    private final NicksCore core;
    /** What each modded client currently holds; decides when a texture PNG must be (re)sent. */
    private final TextureLedger ledger = new TextureLedger();

    public IdentityNetwork(NicksCore core) { this.core = core; }

    /** Call once during mod init (both dedicated server and client need the payload types registered). */
    public static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(IdentitySyncPayload.ID, IdentitySyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SkinTexturePayload.ID, SkinTexturePayload.CODEC);
    }

    /** A player joined: give them every active identity of online players; show their own identity to everyone else. */
    public void onJoin(ServerPlayerEntity joiner) {
        if (!core.config.network.enableSync) return;
        for (ServerPlayerEntity p : core.server.getPlayerManager().getPlayerList()) {
            core.manager.get(p.getUuid()).filter(PlayerIdentity::active).ifPresent(id -> sendTo(joiner, id));
        }
        core.manager.get(joiner.getUuid()).filter(PlayerIdentity::active).ifPresent(id -> {
            for (ServerPlayerEntity other : core.server.getPlayerManager().getPlayerList()) {
                if (other != joiner) sendTo(other, id);
            }
        });
    }

    public void onDisconnect(UUID uuid) {
        ledger.forgetViewer(uuid);
        ledger.forgetPlayer(uuid);
        core.formatter.forget(uuid);
        // tell the remaining clients to drop the departed player's identity and free its textures (revision -1 = forget)
        IdentitySyncPayload forget = new IdentitySyncPayload(uuid, true, "", (byte) 0, "original", "", "",
                IdentitySyncPayload.NONE, (byte) 0, -1L, false);
        for (ServerPlayerEntity viewer : core.server.getPlayerManager().getPlayerList()) {
            if (viewer.getUuid().equals(uuid) || !ServerPlayNetworking.canSend(viewer, IdentitySyncPayload.ID)) continue;
            try {
                ServerPlayNetworking.send(viewer, forget);
            } catch (RuntimeException e) {
                NicksLog.warn("Could not send identity removal to " + viewer.getUuid(), e);
            }
        }
    }

    /** Push an identity (or its removal) to every connected client that has the mod. @return clients updated */
    public int broadcast(PlayerIdentity id) {
        if (!core.config.network.enableSync) return 0;
        int n = 0;
        for (ServerPlayerEntity viewer : core.server.getPlayerManager().getPlayerList()) if (sendTo(viewer, id)) n++;
        NicksLog.debug("Client synchronization completed uuid=" + id.playerUuid() + " rev=" + id.revision() + " clients=" + n);
        return n;
    }

    /** Mark textures as not delivered anywhere (/nicks skin refresh), so the PNGs are sent again. */
    public void invalidate(String skinHash, String capeHash) { ledger.invalidate(skinHash, capeHash); }

    public int moddedViewers() {
        int n = 0;
        for (ServerPlayerEntity p : core.server.getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(p, IdentitySyncPayload.ID)) n++;
        }
        return n;
    }

    private boolean sendTo(ServerPlayerEntity viewer, PlayerIdentity id) {
        if (!ServerPlayNetworking.canSend(viewer, IdentitySyncPayload.ID)) return false;
        try {
            UUID vid = viewer.getUuid();
            String skinHash = "", capeHash = "";
            UUID defaultSource = IdentitySyncPayload.NONE;
            if (id.hasAppearance()) {
                SkinPipeline.Textures t = core.pipeline.textures(id);
                if (id.defaultSkin()) {
                    if (id.officialProfileUuid() != null) defaultSource = id.officialProfileUuid();
                } else if (t.skin() == null) {
                    NicksLog.warn("Skin texture for " + id.playerUuid() + " is missing; run /nicks skin refresh. Sending name only.");
                } else if (fits(t.skin())) {
                    skinHash = t.skin().hash();
                    if (ledger.skinNeeded(vid, skinHash)) ServerPlayNetworking.send(viewer, new SkinTexturePayload(SkinTexturePayload.KIND_SKIN, skinHash, t.skin().png()));
                } else {
                    NicksLog.warn("Skin texture for " + id.playerUuid() + " exceeds the payload limit; not sent");
                }
                if (!id.capeHash().isEmpty()) {
                    if (t.cape() == null) {
                        NicksLog.warn("Cape texture for " + id.playerUuid() + " is missing; run /nicks skin refresh. No cape is shown.");
                    } else if (fits(t.cape())) {
                        capeHash = t.cape().hash();
                        if (ledger.capeNeeded(vid, capeHash)) ServerPlayNetworking.send(viewer, new SkinTexturePayload(SkinTexturePayload.KIND_CAPE, capeHash, t.cape().png()));
                    } else {
                        NicksLog.warn("Cape texture for " + id.playerUuid() + " exceeds the payload limit; not sent");
                    }
                }
            }
            ServerPlayNetworking.send(viewer, new IdentitySyncPayload(id.playerUuid(), !id.active(),
                    id.nickname() == null ? "" : id.nickname(), (byte) id.identityType().ordinal(), id.skinSource(),
                    skinHash, capeHash, defaultSource, (byte) id.skinModel().ordinal(), id.revision(),
                    core.config.display.replaceNametags));
            ledger.record(vid, id.playerUuid(), skinHash, capeHash);
            return true;
        } catch (RuntimeException e) {
            NicksLog.warn("Could not synchronize identity to " + viewer.getUuid(), e); // never silently ignored
            return false;
        }
    }

    private boolean fits(SkinData d) {
        return d.png().length <= Math.min(core.config.network.maxTexturePayloadBytes, SkinTexturePayload.MAX_BYTES);
    }
}
