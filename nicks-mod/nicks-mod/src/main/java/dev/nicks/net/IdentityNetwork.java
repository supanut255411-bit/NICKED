package dev.nicks.net;

import dev.nicks.NicksCore;
import dev.nicks.core.NicksLog;
import dev.nicks.core.PlayerIdentity;
import dev.nicks.core.SkinData;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative identity synchronisation. Clients without the mod are simply skipped (they still get every
 * name change through vanilla packets, but not custom skins or nametags).
 */
public final class IdentityNetwork {
    private final NicksCore core;
    /**
     * viewer -> (player -> skin hash that client currently holds for that player). Mirrors the client's own bookkeeping:
     * a client frees a texture as soon as no identity references it, so a texture is (re)sent whenever the hash is not
     * currently referenced by any identity on that client. (A plain "already sent" set would break A -> B -> A switches.)
     */
    private final Map<UUID, Map<UUID, String>> held = new ConcurrentHashMap<>();

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
        held.remove(uuid);            // this client is gone
        core.formatter.forget(uuid);
        // tell the remaining clients to drop the departed player's identity and free its texture (revision -1 = forget)
        IdentitySyncPayload forget = new IdentitySyncPayload(uuid, true, "", (byte) 0, "original", "", (byte) 0, -1L, false);
        for (ServerPlayerEntity viewer : core.server.getPlayerManager().getPlayerList()) {
            Map<UUID, String> m = held.get(viewer.getUuid());
            if (m != null) m.remove(uuid);
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

    /** Mark a texture as not delivered anywhere (used by /nicks skin refresh so the PNG is sent again). */
    public void invalidate(String hash) {
        if (hash == null || hash.isEmpty()) return;
        for (Map<UUID, String> m : held.values()) m.values().removeIf(hash::equals);
    }

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
            Map<UUID, String> mine = held.computeIfAbsent(viewer.getUuid(), k -> new ConcurrentHashMap<>());
            String hash = "";
            if (id.hasSkinOverride()) {
                SkinData skin = core.pipeline.skinFor(id);
                if (skin == null) {
                    NicksLog.warn("Skin texture for " + id.playerUuid() + " is missing; run /nicks skin refresh. Sending name only.");
                } else if (skin.png().length > Math.min(core.config.network.maxTexturePayloadBytes, SkinTexturePayload.MAX_BYTES)) {
                    NicksLog.warn("Skin texture for " + id.playerUuid() + " exceeds the payload limit; not sent");
                } else {
                    hash = skin.hash();
                    if (!mine.containsValue(hash)) ServerPlayNetworking.send(viewer, new SkinTexturePayload(hash, skin.png()));
                }
            }
            ServerPlayNetworking.send(viewer, new IdentitySyncPayload(id.playerUuid(), !id.active(),
                    id.nickname() == null ? "" : id.nickname(), (byte) id.identityType().ordinal(), id.skinSource(), hash,
                    (byte) id.skinModel().ordinal(), id.revision(), core.config.display.replaceNametags));
            if (hash.isEmpty()) mine.remove(id.playerUuid());
            else mine.put(id.playerUuid(), hash);
            return true;
        } catch (RuntimeException e) {
            NicksLog.warn("Could not synchronize identity to " + viewer.getUuid(), e); // never silently ignored
            return false;
        }
    }
}
