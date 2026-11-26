package dev.nicks.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Resolves a name against the official Minecraft profile services (name lookup, then session-server profile).
 * Async, de-duplicated, rate-limited, retried with exponential backoff and cached. An API failure is always
 * reported as {@link ProfileLookupResult.Unresolved}, never as "not found".
 */
public final class OfficialProfileResolver implements AutoCloseable {
    private record NameProfile(UUID id, String name) {}

    private record Step<T>(T value, String error, boolean retry, long retryAfterMs) {
        static <T> Step<T> ok(T v) { return new Step<>(v, null, false, 0); }
        static <T> Step<T> fail(String e) { return new Step<>(null, e, false, 0); }
        static <T> Step<T> retry(String e, long afterMs) { return new Step<>(null, e, true, afterMs); }
    }

    private record Cached(ProfileLookupResult result, long expiresAt) {}

    private final HttpTransport http;
    private final NicksConfig cfg;
    private final ExecutorService executor;
    private final Map<String, CompletableFuture<ProfileLookupResult>> inflight = new ConcurrentHashMap<>();
    private final Map<String, Cached> cache;
    private final RateLimiter limiter;

    public OfficialProfileResolver(HttpTransport http, NicksConfig cfg) {
        this.http = http;
        this.cfg = cfg;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Nicks-Profile");
            t.setDaemon(true);
            return t;
        });
        this.limiter = new RateLimiter(cfg.network.requestsPerMinute);
        final int max = cfg.skin.maxCacheEntries;
        this.cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> e) { return size() > max; }
        };
    }

    /** Never blocks; the future always completes normally. */
    public CompletableFuture<ProfileLookupResult> lookup(String name, boolean bypassCache) {
        if (!Nicknames.isValidAccountName(name)) {
            return CompletableFuture.completedFuture(new ProfileLookupResult.NotFound()); // cannot be an account name
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (!bypassCache) {
            Cached c = cacheGet(key);
            if (c != null) return CompletableFuture.completedFuture(c.result());
        }
        CompletableFuture<ProfileLookupResult> mine = new CompletableFuture<>();
        CompletableFuture<ProfileLookupResult> existing = inflight.putIfAbsent(key, mine);
        if (existing != null) return existing; // concurrent lookups for the same name share one request
        try {
            executor.execute(() -> {
                ProfileLookupResult result;
                try {
                    NicksLog.debug("Profile lookup started: " + name);
                    result = resolve(name, key);
                    NicksLog.debug("Profile lookup completed: " + name + " -> " + result.getClass().getSimpleName());
                } catch (Throwable t) {
                    result = new ProfileLookupResult.Unresolved("internal error: " + t);
                }
                // Leave the in-flight map BEFORE completing, so a caller that reacts to this result and looks the
                // name up again always starts a fresh lookup (or hits the cache) instead of reusing a stale future.
                inflight.remove(key, mine);
                mine.complete(result);
            });
        } catch (RejectedExecutionException e) {
            inflight.remove(key, mine);
            mine.complete(new ProfileLookupResult.Unresolved("resolver is shut down"));
        }
        return mine;
    }

    private ProfileLookupResult resolve(String name, String key) {
        Step<Optional<NameProfile>> s = withRetry(() -> lookupName(name));
        if (s.value() == null) return new ProfileLookupResult.Unresolved(s.error());
        if (s.value().isEmpty()) {
            ProfileLookupResult nf = new ProfileLookupResult.NotFound();
            cachePut(key, nf, cfg.skin.negativeCacheMinutes);
            return nf;
        }
        NameProfile np = s.value().get();
        Step<ProfileLookupResult.Real> p = withRetry(() -> fetchProfile(np));
        if (p.value() == null) return new ProfileLookupResult.Unresolved(p.error());
        cachePut(key, p.value(), cfg.skin.profileCacheMinutes);
        return p.value();
    }

    // ---- HTTP steps ------------------------------------------------------

    private Step<Optional<NameProfile>> lookupName(String name) {
        HttpTransport.Response r;
        try {
            if (!limiter.acquire(cfg.network.maxRateLimitWaitSeconds * 1000L)) return Step.fail("local request limit reached");
            r = http.get(URI.create(cfg.network.nameLookupUrl + URLEncoder.encode(name, StandardCharsets.UTF_8)),
                    Duration.ofSeconds(cfg.skin.downloadTimeoutSeconds), 16384);
        } catch (IOException e) {
            return Step.retry("network error: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Step.fail("interrupted");
        }
        int st = r.status();
        if (st == 200) {
            try {
                Map<String, Object> m = Json.parseObject(utf8(r.body()));
                UUID id = parseUndashed(Json.str(m, "id", ""));
                String n = Json.str(m, "name", "");
                if (n.isEmpty()) return Step.fail("malformed profile response");
                return Step.ok(Optional.of(new NameProfile(id, n)));
            } catch (RuntimeException e) {
                return Step.fail("malformed profile response");
            }
        }
        if (st == 204) return Step.ok(Optional.empty());
        if (st == 404) {
            try { // only a genuine NOT_FOUND body (or an empty one) counts as "does not exist"
                String body = utf8(r.body()).trim();
                if (!body.isEmpty()) {
                    String type = Json.str(Json.parseObject(body), "errorType", "NOT_FOUND");
                    if (!type.equals("NOT_FOUND")) return Step.fail("unexpected 404: " + type);
                }
            } catch (RuntimeException e) {
                return Step.fail("unparseable 404 response");
            }
            return Step.ok(Optional.empty());
        }
        return statusProblem(st, r);
    }

    private Step<ProfileLookupResult.Real> fetchProfile(NameProfile np) {
        HttpTransport.Response r;
        try {
            if (!limiter.acquire(cfg.network.maxRateLimitWaitSeconds * 1000L)) return Step.fail("local request limit reached");
            r = http.get(URI.create(cfg.network.sessionProfileUrl + np.id().toString().replace("-", "") + "?unsigned=true"),
                    Duration.ofSeconds(cfg.skin.downloadTimeoutSeconds), 65536);
        } catch (IOException e) {
            return Step.retry("network error: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Step.fail("interrupted");
        }
        if (r.status() == 200) {
            try {
                return Step.ok(parseProfile(np, r.body()));
            } catch (RuntimeException e) {
                return Step.fail("malformed session profile");
            }
        }
        if (r.status() == 204 || r.status() == 404) return Step.fail("session server has no profile data for this account");
        return statusProblem(r.status(), r);
    }

    private <T> Step<T> statusProblem(int st, HttpTransport.Response r) {
        if (st == 429) {
            long ra = 0;
            try { ra = Long.parseLong(r.headers().getOrDefault("retry-after", "0").trim()) * 1000L; } catch (NumberFormatException ignored) { }
            return Step.retry("rate limited by Mojang (HTTP 429)", Math.min(ra, 5000));
        }
        if (st >= 500) return Step.retry("Mojang service error (HTTP " + st + ")", 0);
        return Step.fail("unexpected HTTP " + st);
    }

    private <T> Step<T> withRetry(Supplier<Step<T>> op) {
        int attempts = cfg.network.retryLimit;
        Step<T> last = Step.fail("no attempt made");
        for (int i = 0; i < attempts; i++) {
            last = op.get();
            if (!last.retry()) return last;
            if (i == attempts - 1) break;
            long wait = Math.max(last.retryAfterMs(), (long) cfg.network.retryBackoffMillis << i); // exponential backoff
            try {
                TimeUnit.MILLISECONDS.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Step.fail("interrupted");
            }
        }
        return Step.fail(last.error());
    }

    // ---- parsing ---------------------------------------------------------

    static ProfileLookupResult.Real parseProfile(NameProfile np, byte[] body) {
        Map<String, Object> m = Json.parseObject(utf8(body));
        String skinUrl = null;
        SkinModel model = SkinModel.CLASSIC;
        for (Object o : Json.list(m, "properties")) {
            if (!(o instanceof Map<?, ?> pm) || !"textures".equals(pm.get("name")) || !(pm.get("value") instanceof String v)) continue;
            Map<String, Object> tex = Json.parseObject(new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8));
            Map<String, Object> skin = Json.obj(Json.obj(tex, "textures"), "SKIN");
            if (skin != null) {
                skinUrl = Json.str(skin, "url", null);
                model = SkinModel.parse(Json.str(Json.obj(skin, "metadata"), "model", "default"));
            }
        }
        String canonical = Json.str(m, "name", np.name());
        return new ProfileLookupResult.Real(np.id(), canonical, skinUrl, model);
    }

    static UUID parseUndashed(String s) {
        if (s.length() != 32) throw new IllegalArgumentException("bad uuid");
        return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
                + s.substring(16, 20) + "-" + s.substring(20));
    }

    private static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    // ---- cache -----------------------------------------------------------

    private Cached cacheGet(String key) {
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && c.expiresAt() < System.currentTimeMillis()) { cache.remove(key); return null; }
            return c;
        }
    }

    private void cachePut(String key, ProfileLookupResult r, int minutes) {
        synchronized (cache) {
            cache.put(key, new Cached(r, System.currentTimeMillis() + minutes * 60_000L));
        }
    }

    public void invalidate(String name) {
        synchronized (cache) { cache.remove(name.toLowerCase(Locale.ROOT)); }
    }

    @Override
    public void close() { executor.shutdownNow(); }

    /** Spacing limiter with a small burst allowance. */
    static final class RateLimiter {
        private final long intervalNanos;
        private long next = System.nanoTime();

        RateLimiter(int perMinute) { intervalNanos = 60_000_000_000L / Math.max(1, perMinute); }

        boolean acquire(long maxWaitMs) throws InterruptedException {
            long waitNs;
            synchronized (this) {
                long now = System.nanoTime();
                long slot = Math.max(next, now - 4 * intervalNanos);
                waitNs = Math.max(0, slot - now);
                if (waitNs > maxWaitMs * 1_000_000L) return false;
                next = slot + intervalNanos;
            }
            if (waitNs > 0) TimeUnit.NANOSECONDS.sleep(waitNs);
            return true;
        }
    }
}
