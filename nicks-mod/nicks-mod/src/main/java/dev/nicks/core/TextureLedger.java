package dev.nicks.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side mirror of what each modded client currently holds: viewer -> (player -> skin hash, cape hash).
 * A client frees a texture as soon as no identity references it, so a PNG must be (re)sent whenever its hash is not
 * currently referenced on that client. (A plain "already sent" set breaks A -> B -> A switches.)
 */
public final class TextureLedger {
    public record Held(String skinHash, String capeHash) {}

    private final Map<UUID, Map<UUID, Held>> held = new ConcurrentHashMap<>();

    public boolean skinNeeded(UUID viewer, String hash) {
        return !hash.isEmpty() && held.getOrDefault(viewer, Map.of()).values().stream().noneMatch(h -> hash.equals(h.skinHash()));
    }

    public boolean capeNeeded(UUID viewer, String hash) {
        return !hash.isEmpty() && held.getOrDefault(viewer, Map.of()).values().stream().noneMatch(h -> hash.equals(h.capeHash()));
    }

    /** Record what this viewer now holds for this player (both empty = nothing, entry removed). */
    public void record(UUID viewer, UUID player, String skinHash, String capeHash) {
        Map<UUID, Held> m = held.computeIfAbsent(viewer, k -> new ConcurrentHashMap<>());
        if (skinHash.isEmpty() && capeHash.isEmpty()) m.remove(player);
        else m.put(player, new Held(skinHash, capeHash));
    }

    public void forgetViewer(UUID viewer) { held.remove(viewer); }

    public void forgetPlayer(UUID player) {
        for (Map<UUID, Held> m : held.values()) m.remove(player);
    }

    /** Pretend this texture was never delivered anywhere (used by /nicks skin refresh). */
    public void invalidate(String skinHash, String capeHash) {
        for (Map<UUID, Held> m : held.values()) {
            for (Map.Entry<UUID, Held> e : new HashMap<>(m).entrySet()) {
                Held h = e.getValue();
                String s = h.skinHash().equals(skinHash) ? "" : h.skinHash();
                String c = h.capeHash().equals(capeHash) ? "" : h.capeHash();
                if (s.isEmpty() && c.isEmpty()) m.remove(e.getKey()); else m.put(e.getKey(), new Held(s, c));
            }
        }
    }
}
