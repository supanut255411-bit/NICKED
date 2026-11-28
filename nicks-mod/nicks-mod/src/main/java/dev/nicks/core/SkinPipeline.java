package dev.nicks.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns a nickname / skin-source name into a ready-to-apply {@link Appearance} (skin + cape):
 * a real account (REAL_ACCOUNT: its official skin and cape, resolved from ONE profile response), a nickname confirmed
 * not to be an account (FICTIONAL: a real skin borrowed from {@link BorrowedSkinRegistry}, never a generated one) or a
 * failure that must leave the previous identity untouched. All methods block: call them off the server thread.
 */
public final class SkinPipeline {
    public sealed interface Outcome permits Resolved, Failed {}

    public record Resolved(IdentityType type, String skinSource, Appearance appearance, String canonicalName) implements Outcome {}

    public record Failed(String reason, boolean transientError) implements Outcome {}

    /** The texture PNGs an identity references (either may be null). */
    public record Textures(SkinData skin, SkinData cape) {}

    private final NicksConfig cfg;
    private final OfficialProfileResolver resolver;
    private final SkinTextureDownloader downloader;
    private final SkinTextureCache skinCache, capeCache;
    private final BorrowedSkinRegistry borrowed;
    private final Map<String, String> urlToHash;

    public SkinPipeline(NicksConfig cfg, OfficialProfileResolver resolver, SkinTextureDownloader downloader,
                        SkinTextureCache skinCache, SkinTextureCache capeCache, BorrowedSkinRegistry borrowed) {
        this.cfg = cfg;
        this.resolver = resolver;
        this.downloader = downloader;
        this.skinCache = skinCache;
        this.capeCache = capeCache;
        this.borrowed = borrowed;
        final int max = cfg.skin.maxCacheEntries * 2;
        this.urlToHash = new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > max; }
        };
    }

    /** Nickname -> real account (official skin + cape) or a borrowed real skin when the account does not exist. */
    public Outcome forNickname(UUID player, String nickname, boolean bypassCache) {
        if (!cfg.skin.enableOfficialResolution || !Nicknames.isValidAccountName(nickname)) return borrowed(nickname, false);
        ProfileLookupResult r = await(nickname, bypassCache);
        if (r instanceof ProfileLookupResult.Real real) {
            NicksLog.debug("Account classified as REAL: " + nickname);
            return official(real, bypassCache);
        }
        if (r instanceof ProfileLookupResult.NotFound) {
            NicksLog.debug("Account classified as FICTIONAL: " + nickname);
            return borrowed(nickname, false);
        }
        return new Failed("Minecraft profile could not be resolved: " + ((ProfileLookupResult.Unresolved) r).reason(), true);
    }

    /** Skin AND cape of a specific real account (/nicks skin set &lt;targets&gt; &lt;source&gt; and refresh). */
    public Outcome forSkinSource(String account, boolean bypassCache) {
        if (!cfg.skin.enableOfficialResolution) return new Failed("Official profile resolution is disabled in the config", false);
        ProfileLookupResult r = await(account, bypassCache);
        if (r instanceof ProfileLookupResult.Real real) return official(real, bypassCache);
        if (r instanceof ProfileLookupResult.NotFound) return new Failed("'" + account + "' is not a registered Minecraft account", false);
        return new Failed("Minecraft profile could not be resolved: " + ((ProfileLookupResult.Unresolved) r).reason(), true);
    }

    /** A real skin borrowed for a nickname that is not an account (forceNew = pick a different one). */
    public Outcome borrowed(String nickname, boolean forceNew) {
        try {
            Appearance a = borrowed.appearanceFor(nickname, forceNew);
            return new Resolved(IdentityType.FICTIONAL, "borrowed:" + a.sourceName(), a, nickname);
        } catch (SkinException e) {
            return new Failed(e.getMessage(), true);
        }
    }

    /** Texture PNGs for an identity's stored hashes (for network sync); never touches the network. */
    public Textures textures(PlayerIdentity id) {
        SkinData skin = id.skinHash().isEmpty() ? null : skinCache.get(id.skinHash(), id.skinModel()).orElse(null);
        SkinData cape = id.capeHash().isEmpty() ? null : capeCache.get(id.capeHash(), SkinModel.CLASSIC).orElse(null);
        return new Textures(skin, cape);
    }

    private Outcome official(ProfileLookupResult.Real real, boolean bypassCache) {
        try {
            SkinData skin = null; // null = the account has no custom skin: its built-in default skin applies
            if (real.skinUrl() != null) {
                skin = cached(real.skinUrl(), bypassCache, skinCache, real.model());
                if (skin == null) {
                    skin = downloader.download(real.skinUrl(), real.model());
                    synchronized (urlToHash) { urlToHash.put(real.skinUrl(), skin.hash()); }
                }
                skinCache.put(skin);
            }
            SkinData cape = null; // no CAPE entry in the profile = no cape (never invented, never kept from before)
            if (real.capeUrl() != null) {
                cape = cached(real.capeUrl(), bypassCache, capeCache, SkinModel.CLASSIC);
                if (cape == null) {
                    cape = downloader.downloadCape(real.capeUrl());
                    synchronized (urlToHash) { urlToHash.put(real.capeUrl(), cape.hash()); }
                }
                capeCache.put(cape);
            }
            Appearance a = new Appearance(real.profileUuid(), real.canonicalName(), "official", skin, real.model(), cape,
                    cape != null, real.signature(), System.currentTimeMillis());
            return new Resolved(IdentityType.REAL_ACCOUNT, "official:" + real.canonicalName(), a, real.canonicalName());
        } catch (SkinException e) {
            // never half-apply: if the cape (or skin) cannot be fetched, the whole change fails and the old look stays
            return new Failed("Texture download failed: " + e.getMessage(), true);
        }
    }

    private SkinData cached(String url, boolean bypass, SkinTextureCache cache, SkinModel model) {
        if (bypass) return null;
        String hash;
        synchronized (urlToHash) { hash = urlToHash.get(url); }
        return hash == null ? null : cache.get(hash, model).orElse(null);
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
