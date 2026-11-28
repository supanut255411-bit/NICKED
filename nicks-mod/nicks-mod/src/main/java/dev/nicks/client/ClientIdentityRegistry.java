package dev.nicks.client;

import dev.nicks.NicksRuntime;
import dev.nicks.compat.SkinCompat;
import dev.nicks.compat.TextureCompat;
import dev.nicks.net.IdentitySyncPayload;
import dev.nicks.net.SkinTexturePayload;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Client-side copy of the server's identities. Registers each skin / cape PNG once as a dynamic texture (shared by every
 * player using it, skins and capes in separate maps), rebuilds SkinTextures only when an identity's revision changes,
 * ignores stale revisions and frees textures nobody uses any more. Mutated and read on the client thread only.
 */
public final class ClientIdentityRegistry implements NicksRuntime.ClientView {
    private static final Logger LOG = LoggerFactory.getLogger("Nicks");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private record Entry(String nickname, Text nametag, String skinHash, String capeHash, UUID defaultSource, boolean slim, long revision) {}

    private record Built(long revision, SkinTextures skin) {}

    private final Map<UUID, Entry> identities = new ConcurrentHashMap<>();
    private final Map<UUID, Long> revisions = new ConcurrentHashMap<>();
    private final Map<String, Identifier> skinTextures = new ConcurrentHashMap<>();
    private final Map<String, Identifier> capeTextures = new ConcurrentHashMap<>();
    private final Map<UUID, Built> built = new ConcurrentHashMap<>();

    public void onTexture(byte kind, String hash, byte[] png) {
        boolean cape = kind == SkinTexturePayload.KIND_CAPE;
        Map<String, Identifier> map = cape ? capeTextures : skinTextures;
        if (!HASH.matcher(hash).matches() || map.containsKey(hash)) return;
        Identifier id = Identifier.of("nicks", (cape ? "cape/" : "skin/") + hash);
        try {
            TextureCompat.register(id, png);
            map.put(hash, id);
            LOG.debug("{} texture registered {}", cape ? "Cape" : "Skin", hash.substring(0, 8));
        } catch (Exception e) {
            LOG.warn("Could not register {} texture {}: {}", cape ? "cape" : "skin", hash.substring(0, 8), e.toString());
        }
    }

    public void onIdentity(IdentitySyncPayload p) {
        if (p.revision() < 0) { // the server says this player left: forget everything about them
            identities.remove(p.uuid());
            revisions.remove(p.uuid());
            built.remove(p.uuid());
            releaseUnused();
            return;
        }
        if (p.revision() <= revisions.getOrDefault(p.uuid(), -1L)) return; // stale or duplicate
        revisions.put(p.uuid(), p.revision());
        built.remove(p.uuid());
        if (p.cleared()) {
            identities.remove(p.uuid());
        } else {
            boolean named = p.nametag() && !p.nickname().isEmpty();
            identities.put(p.uuid(), new Entry(p.nickname(), named ? Text.literal(p.nickname()) : null, p.skinHash(),
                    p.capeHash(), p.defaultSource(), p.model() == 1, p.revision()));
        }
        releaseUnused();
    }

    @Override
    public Text nicknameOf(UUID player) {
        Entry e = identities.get(player);
        return e == null ? null : e.nametag();
    }

    /**
     * Appearance override for this profile UUID, or null to keep whatever vanilla resolved (also the case when the
     * identity has no skin/cape of its own, e.g. after /nicks reset: original skin and cape show again). When an
     * override is active the cape is exactly the source's cape, or none; never the target's own cape.
     */
    public SkinTextures skinFor(UUID profileId, SkinTextures original) {
        Entry e = identities.get(profileId);
        if (e == null) return null;
        boolean custom = !e.skinHash().isEmpty();
        boolean builtIn = !IdentitySyncPayload.NONE.equals(e.defaultSource());
        if (!custom && !builtIn) return null;
        Identifier skin = custom ? skinTextures.get(e.skinHash()) : null;
        if (custom && skin == null) return null; // PNG not here (yet): keep the normal skin rather than a blank one
        Identifier cape = e.capeHash().isEmpty() ? null : capeTextures.get(e.capeHash());
        Built b = built.get(profileId);
        if (b != null && b.revision() == e.revision()) return b.skin();
        SkinTextures result = SkinCompat.build(skin, cape, e.slim(), custom ? null : e.defaultSource());
        built.put(profileId, new Built(e.revision(), result));
        return result;
    }

    public Collection<String> nicknames() {
        return identities.values().stream().map(Entry::nickname).filter(n -> !n.isEmpty()).toList();
    }

    public void clear() {
        for (Identifier id : skinTextures.values()) TextureCompat.release(id);
        for (Identifier id : capeTextures.values()) TextureCompat.release(id);
        skinTextures.clear();
        capeTextures.clear();
        identities.clear();
        revisions.clear();
        built.clear();
    }

    private void releaseUnused() {
        Set<String> skinsInUse = new HashSet<>(), capesInUse = new HashSet<>();
        for (Entry e : identities.values()) {
            skinsInUse.add(e.skinHash());
            capesInUse.add(e.capeHash());
        }
        List.copyOf(skinTextures.keySet()).forEach(h -> { if (!skinsInUse.contains(h)) TextureCompat.release(skinTextures.remove(h)); });
        List.copyOf(capeTextures.keySet()).forEach(h -> { if (!capesInUse.contains(h)) TextureCompat.release(capeTextures.remove(h)); });
    }
}
