package dev.nicks.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns a nickname / skin-source name into a ready-to-apply skin: official account (REAL_ACCOUNT, official skin),
 * confirmed-missing account (FICTIONAL, unique generated/asset skin) or a failure that must leave the previous
 * identity untouched. All methods block, so call them off the server thread.
 */
public final class SkinPipeline {
    public sealed interface Outcome permits Resolved, Failed {}

    public record Resolved(IdentityType type, String skinSource, SkinData skin, UUID officialUuid, String canonicalName,
                           String fictionalId) implements Outcome {}

    public record Failed(String reason, boolean transientError) implements Outcome {}

    private final NicksConfig cfg;
    private final OfficialProfileResolver resolver;
    private final SkinTextureDownloader downloader;
    private final SkinTextureCache cache;
    private final FictionalSkinRegistry fictional;
    private final Map<String, String> urlToHash;

    public SkinPipeline(NicksConfig cfg, OfficialProfileResolver resolver, SkinTextureDownloader downloader,
                        SkinTextureCache cache, FictionalSkinRegistry fictional) {
        this.cfg = cfg;
        this.resolver = resolver;
        this.downloader = downloader;
        this.cache = cache;
        this.fictional = fictional;
        final int max = cfg.skin.maxCacheEntries;
        this.urlToHash = new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > max; }
        };
    }

    /** Nickname -> real account (official skin) or fictional (assigned skin). */
    public Outcome forNickname(UUID player, String nickname, boolean bypassCache) {
        if (!cfg.skin.enableOfficialResolution || !Nicknames.isValidAccountName(nickname)) {
            return fictional(player, nickname, false);
        }
        ProfileLookupResult r = await(nickname, bypassCache);
        if (r instanceof ProfileLookupResult.Real real) {
            NicksLog.debug("Account classified as REAL: " + nickname);
            return official(real, bypassCache);
        }
        if (r instanceof ProfileLookupResult.NotFound) {
            NicksLog.debug("Account classified as FICTIONAL: " + nickname);
            return fictional(player, nickname, false);
        }
        return new Failed("Minecraft profile could not be resolved: " + ((ProfileLookupResult.Unresolved) r).reason(), true);
    }

    /** Skin of a specific real account (used by /nicks skin set &lt;targets&gt; &lt;source&gt; and refresh). */
    public Outcome forSkinSource(String account, boolean bypassCache) {
        if (!cfg.skin.enableOfficialResolution) return new Failed("Official profile resolution is disabled in the config", false);
        ProfileLookupResult r = await(account, bypassCache);
        if (r instanceof ProfileLookupResult.Real real) return official(real, bypassCache);
        if (r instanceof ProfileLookupResult.NotFound) return new Failed("'" + account + "' is not a registered Minecraft account", false);
        return new Failed("Minecraft profile could not be resolved: " + ((ProfileLookupResult.Unresolved) r).reason(), true);
    }

    public Outcome fictional(UUID player, String nickname, boolean forceNew) {
        if (!cfg.skin.enableFictionalSkins) return new Failed("Fictional skins are disabled in the config", false);
        try {
            FictionalSkinRegistry.Assignment a = fictional.assign(player, nickname, forceNew);
            return new Resolved(IdentityType.FICTIONAL, "fictional:" + a.entry().id(), a.skin(), null, nickname, a.entry().id());
        } catch (SkinException e) {
            return new Failed(e.getMessage(), false);
        }
    }

    /** Skin pixels for an identity's stored hash (for network sync), or null if they cannot be found. */
    public SkinData skinFor(PlayerIdentity id) {
        if (!id.hasSkinOverride()) return null;
        var cached = cache.get(id.skinHash(), id.skinModel());
        if (cached.isPresent()) return cached.get();
        if (id.identityType() == IdentityType.FICTIONAL) {
            return fictional.byPlayer(id.playerUuid()).map(fictional::load).orElse(null);
        }
        return null;
    }

    private Outcome official(ProfileLookupResult.Real real, boolean bypassCache) {
        try {
            SkinData skin;
            if (real.skinUrl() == null) {
                skin = FictionalSkinGenerator.defaultSkin(real.model());
            } else {
                String known;
                synchronized (urlToHash) { known = bypassCache ? null : urlToHash.get(real.skinUrl()); }
                var hit = known == null ? java.util.Optional.<SkinData>empty() : cache.get(known, real.model());
                if (hit.isPresent()) {
                    skin = hit.get();
                } else {
                    skin = downloader.download(real.skinUrl(), real.model());
                    synchronized (urlToHash) { urlToHash.put(real.skinUrl(), skin.hash()); }
                }
            }
            cache.put(skin);
            return new Resolved(IdentityType.REAL_ACCOUNT, "official:" + real.canonicalName(), skin, real.profileUuid(),
                    real.canonicalName(), "");
        } catch (SkinException e) {
            return new Failed("Skin download failed: " + e.getMessage(), true);
        }
    }

    private ProfileLookupResult await(String name, boolean bypassCache) {
        try {
            long wait = cfg.network.maxRateLimitWaitSeconds + (long) cfg.skin.downloadTimeoutSeconds * cfg.network.retryLimit * 2 + 10;
            return resolver.lookup(name, bypassCache).get(wait, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProfileLookupResult.Unresolved("interrupted");
        } catch (ExecutionException | TimeoutException e) {
            return new ProfileLookupResult.Unresolved("lookup timed out or failed");
        }
    }
}
