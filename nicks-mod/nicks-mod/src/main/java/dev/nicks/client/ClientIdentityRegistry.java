package dev.nicks.client;

import dev.nicks.NicksRuntime;
import dev.nicks.compat.SkinCompat;
import dev.nicks.compat.TextureCompat;
import dev.nicks.net.IdentitySyncPayload;
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
 * Client-side copy of the server's identities. Registers each skin PNG once as a dynamic texture (shared by every
 * player using it), rebuilds SkinTextures only when an identity's revision changes, ignores stale/out-of-order
 * revisions and frees textures nobody uses any more. Mutated and read on the client thread only.
 */
public final class ClientIdentityRegistry implements NicksRuntime.ClientView {
    private static final Logger LOG = LoggerFactory.getLogger("Nicks");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private record Entry(String nickname, Text nametag, String hash, boolean slim, long revision) {}

    private record Built(long revision, SkinTextures skin) {}

    private final Map<UUID, Entry> identities = new ConcurrentHashMap<>();
    private final Map<UUID, Long> revisions = new ConcurrentHashMap<>();
    private final Map<String, Identifier> textures = new ConcurrentHashMap<>();
    private final Map<UUID, Built> built = new ConcurrentHashMap<>();

    public void onTexture(String hash, byte[] png) {
        if (!HASH.matcher(hash).matches() || textures.containsKey(hash)) return;
        Identifier id = Identifier.of("nicks", "skin/" + hash);
        try {
            TextureCompat.register(id, png);
            textures.put(hash, id);
            LOG.debug("Skin texture registered {}", hash.substring(0, 8));
        } catch (Exception e) {
            LOG.warn("Could not register skin texture {}: {}", hash.substring(0, 8), e.toString());
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
            identities.put(p.uuid(), new Entry(p.nickname(), named ? Text.literal(p.nickname()) : null,
                    p.skinHash(), p.model() == 1, p.revision()));
        }
        releaseUnused();
    }

    @Override
    public Text nicknameOf(UUID player) {
        Entry e = identities.get(player);
        return e == null ? null : e.nametag();
    }

    /** Skin override for this profile UUID, or null to keep whatever vanilla resolved. */
    public SkinTextures skinFor(UUID profileId, SkinTextures original) {
        Entry e = identities.get(profileId);
        if (e == null || e.hash().isEmpty()) return null;
        Identifier tex = textures.get(e.hash());
        if (tex == null) return null; // PNG not here (yet): keep the normal skin rather than a blank one
        Built b = built.get(profileId);
        if (b != null && b.revision() == e.revision()) return b.skin();
        SkinTextures skin = SkinCompat.build(tex, e.slim(), original);
        built.put(profileId, new Built(e.revision(), skin));
        return skin;
    }

    public Collection<String> nicknames() {
        return identities.values().stream().map(Entry::nickname).filter(n -> !n.isEmpty()).toList();
    }

    public void clear() {
        for (Identifier id : textures.values()) TextureCompat.release(id);
        textures.clear();
        identities.clear();
        revisions.clear();
        built.clear();
    }

    private void releaseUnused() {
        Set<String> inUse = new HashSet<>();
        for (Entry e : identities.values()) inUse.add(e.hash());
        List.copyOf(textures.keySet()).forEach(hash -> {
            if (!inUse.contains(hash)) {
                TextureCompat.release(textures.remove(hash)); // only when no identity references it
            }
        });
    }
}
