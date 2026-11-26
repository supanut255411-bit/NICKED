package dev.nicks.core;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Downloads an official skin texture with strict URL, size, format and dimension validation. */
public final class SkinTextureDownloader {
    private static final Pattern TEXTURE_PATH = Pattern.compile("/texture/[0-9a-fA-F]{16,128}");

    private final HttpTransport http;
    private final Predicate<URI> policy;
    private final int maxBytes;
    private final Duration timeout;

    public SkinTextureDownloader(HttpTransport http, NicksConfig cfg) {
        this(http, strictPolicy(cfg.skin.allowedTextureHosts), cfg);
    }

    /** Custom URL policy (tests only; production uses {@link #strictPolicy}). */
    public SkinTextureDownloader(HttpTransport http, Predicate<URI> policy, NicksConfig cfg) {
        this.http = http;
        this.policy = policy;
        this.maxBytes = cfg.skin.maxSkinBytes;
        this.timeout = Duration.ofSeconds(cfg.skin.downloadTimeoutSeconds);
    }

    /** HTTPS only, allow-listed host, default port, no credentials, /texture/<hex> path. */
    public static Predicate<URI> strictPolicy(List<String> allowedHosts) {
        return u -> u.getScheme() != null && u.getScheme().equals("https")
                && u.getHost() != null && allowedHosts.stream().anyMatch(h -> h.equalsIgnoreCase(u.getHost()))
                && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443)
                && u.getQuery() == null && u.getPath() != null && TEXTURE_PATH.matcher(u.getPath()).matches();
    }

    public SkinData download(String url, SkinModel model) throws SkinException {
        URI uri;
        try {
            // Mojang's texture property uses http://; the CDN serves https, which is what we require.
            String fixed = url.startsWith("http://") ? "https://" + url.substring(7) : url;
            uri = URI.create(fixed);
        } catch (IllegalArgumentException e) {
            throw new SkinException("Invalid texture URL");
        }
        if (!policy.test(uri)) throw new SkinException("Texture URL rejected by security policy");
        try {
            HttpTransport.Response r = http.get(uri, timeout, maxBytes);
            if (r.status() != 200) throw new SkinException("Texture server returned HTTP " + r.status());
            return SkinImages.fromPng(r.body(), model, maxBytes);
        } catch (IOException e) {
            throw new SkinException("Texture download failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("Texture download interrupted");
        }
    }
}
