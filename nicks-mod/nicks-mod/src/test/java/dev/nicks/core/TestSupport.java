package dev.nicks.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Shared helpers: scripted HTTP transport, fast config, temp dirs, canned Mojang responses. */
final class TestSupport {
    private TestSupport() {}

    interface Handler { HttpTransport.Response handle(URI uri) throws IOException; }

    static final class FakeHttp implements HttpTransport {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger nameRequests = new AtomicInteger();
        volatile Handler handler;

        FakeHttp(Handler h) { handler = h; }

        @Override public Response get(URI uri, Duration timeout, int maxBytes) throws IOException {
            requests.incrementAndGet();
            if (uri.toString().contains("lookup/name")) nameRequests.incrementAndGet();
            return handler.handle(uri);
        }
    }

    static NicksConfig fastConfig() {
        NicksConfig c = new NicksConfig();
        c.network.retryBackoffMillis = 1;
        c.network.requestsPerMinute = 6_000_000;
        c.network.retryLimit = 3;
        c.storage.saveDelaySeconds = 0;
        c.skin.downloadTimeoutSeconds = 2;
        return c;
    }

    static Path tempDir() {
        try {
            Path p = Files.createTempDirectory("nicks-test");
            p.toFile().deleteOnExit();
            return p;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static HttpTransport.Response resp(int status, String body) {
        return new HttpTransport.Response(status, body.getBytes(StandardCharsets.UTF_8), java.util.Map.of());
    }

    static String undashed(java.util.UUID u) { return u.toString().replace("-", ""); }

    static String sessionJson(java.util.UUID id, String name, String skinUrl, String model) {
        return sessionJson(id, name, skinUrl, model, null);
    }

    static String sessionJson(java.util.UUID id, String name, String skinUrl, String model, String capeUrl) {
        StringBuilder t = new StringBuilder("{\"textures\":{");
        if (skinUrl != null) t.append("\"SKIN\":{\"url\":\"").append(skinUrl).append("\"")
                .append(model == null ? "" : ",\"metadata\":{\"model\":\"" + model + "\"}").append("}");
        if (capeUrl != null) t.append(skinUrl != null ? "," : "").append("\"CAPE\":{\"url\":\"").append(capeUrl).append("\"}");
        t.append("}}");
        return "{\"id\":\"" + undashed(id) + "\",\"name\":\"" + name + "\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + Base64.getEncoder().encodeToString(t.toString().getBytes(StandardCharsets.UTF_8)) + "\",\"signature\":\"SIGNATURE\"}]}";
    }

    /** Transport that knows a set of accounts and answers like Mojang. */
    static Handler mojang(Function<String, java.util.UUID> accounts, Function<String, String> skinUrl, Function<String, String> model) {
        return uri -> {
            String s = uri.toString();
            if (s.contains("lookup/name/")) {
                String name = s.substring(s.lastIndexOf('/') + 1);
                java.util.UUID id = accounts.apply(name.toLowerCase());
                if (id == null) return resp(404, "{\"errorType\":\"NOT_FOUND\",\"error\":\"NOT_FOUND\"}");
                return resp(200, "{\"id\":\"" + undashed(id) + "\",\"name\":\"" + name + "\"}");
            }
            String hex = s.substring(s.lastIndexOf('/') + 1).split("\\?")[0];
            for (String[] acct : new String[][] {{"notch", "Notch"}, {"alexfan", "AlexFan"}}) { // Mojang returns canonical capitalisation
                String n = acct[0];
                java.util.UUID id = accounts.apply(n);
                if (id != null && undashed(id).equals(hex)) return resp(200, sessionJson(id, acct[1], skinUrl.apply(n), model.apply(n)));
            }
            return resp(204, "");
        };
    }

    /** A scripted Mojang + texture server + skin database, with skins and capes as real PNGs. */
    static final class FakeMojang implements HttpTransport {
        record Account(String name, java.util.UUID id, String skinUrl, boolean slim, String capeUrl, String skinHash, String capeHash) {}

        final NicksConfig cfg;
        final java.util.Map<String, Account> byLower = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String, byte[]> textures = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger requests = new AtomicInteger(), bulkRequests = new AtomicInteger();
        volatile boolean down;
        volatile boolean breakCapes;
        volatile String skinDbJson;
        private int counter = 1;

        FakeMojang(NicksConfig cfg) { this.cfg = cfg; }

        private static byte[] png(int w, int h, int rgb, boolean holes) {
            java.awt.image.BufferedImage i = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) i.setRGB(x, y, holes && x < 8 ? 0 : 0xFF000000 | rgb);
            return SkinImages.encodePng(i);
        }

        /** @param capeRgb -1 = the account has no cape */
        synchronized Account add(String name, boolean slim, int capeRgb) {
            int n = counter++;
            String hex = String.format("%032x", n);
            String skinUrl = "http://textures.minecraft.net/texture/" + hex;
            byte[] skin = png(64, 64, (int) ((n * 2654435761L & 0xFFFFFF) | 0x010101), false);
            textures.put("https://textures.minecraft.net/texture/" + hex, skin);
            String capeUrl = null, capeHash = "";
            if (capeRgb >= 0) {
                String chex = String.format("%032x", 1_000_000 + n);
                capeUrl = "http://textures.minecraft.net/texture/" + chex;
                byte[] cape = png(64, 32, capeRgb, true);
                textures.put("https://textures.minecraft.net/texture/" + chex, cape);
                try { capeHash = SkinImages.fromCapePng(cape, 1_000_000).hash(); } catch (SkinException e) { throw new AssertionError(e); }
            }
            String skinHash;
            try { skinHash = SkinImages.fromPng(skin, SkinModel.CLASSIC, 1_000_000).hash(); } catch (SkinException e) { throw new AssertionError(e); }
            Account a = new Account(name, java.util.UUID.nameUUIDFromBytes(name.toLowerCase().getBytes()), skinUrl, slim, capeUrl, skinHash, capeHash);
            byLower.put(name.toLowerCase(), a);
            return a;
        }

        /** An account that never set a custom skin. */
        synchronized Account addDefaultSkin(String name) {
            Account a = new Account(name, java.util.UUID.nameUUIDFromBytes(name.toLowerCase().getBytes()), null, false, null, "", "");
            byLower.put(name.toLowerCase(), a);
            return a;
        }

        @Override public Response get(URI uri, Duration timeout, int maxBytes) {
            requests.incrementAndGet();
            if (down) return resp(503, "down");
            String s = uri.toString();
            if (s.startsWith(cfg.network.nameLookupUrl)) {
                Account a = byLower.get(java.net.URLDecoder.decode(s.substring(cfg.network.nameLookupUrl.length()), StandardCharsets.UTF_8).toLowerCase());
                if (a == null) return resp(404, "{\"errorType\":\"NOT_FOUND\"}");
                return resp(200, "{\"id\":\"" + undashed(a.id()) + "\",\"name\":\"" + a.name() + "\"}");
            }
            if (s.startsWith(cfg.network.sessionProfileUrl)) {
                String hex = s.substring(cfg.network.sessionProfileUrl.length()).split("\\?")[0];
                for (Account a : byLower.values()) if (undashed(a.id()).equals(hex)) {
                    return resp(200, sessionJson(a.id(), a.name(), a.skinUrl(), a.slim() ? "slim" : null, a.capeUrl()));
                }
                return resp(204, "");
            }
            if (s.startsWith("https://textures.minecraft.net/texture/")) {
                byte[] b = textures.get(s);
                if (b == null) return resp(404, "no such texture");
                if (breakCapes && byLower.values().stream().anyMatch(a -> a.capeUrl() != null && a.capeUrl().replace("http://", "https://").equals(s))) return resp(200, "not a png");
                return new Response(200, b, java.util.Map.of());
            }
            if (s.equals(cfg.skin.skinDbUrl)) return skinDbJson == null ? resp(503, "down") : resp(200, skinDbJson);
            return resp(404, "unknown " + s);
        }

        @Override public Response post(URI uri, byte[] body, String contentType, Duration timeout, int maxBytes) {
            requests.incrementAndGet();
            bulkRequests.incrementAndGet();
            if (down) return resp(503, "down");
            java.util.List<?> names = (java.util.List<?>) Json.parse(new String(body, StandardCharsets.UTF_8));
            if (names.size() > 10) return resp(400, "too many names");
            StringBuilder out = new StringBuilder("[");
            for (Object n : names) {
                Account a = byLower.get(String.valueOf(n).toLowerCase());
                if (a == null) continue;
                if (out.length() > 1) out.append(',');
                out.append("{\"id\":\"").append(undashed(a.id())).append("\",\"name\":\"").append(a.name()).append("\"}");
            }
            return resp(200, out.append("]").toString());
        }
    }

    /** A loaded name pool: the given real account names plus {@code fillers} made-up pool entries, shuffled deterministically. */
    static NamePool pool(NicksConfig cfg, java.util.List<String> realNames, int fillers) {
        java.util.List<String> all = new java.util.ArrayList<>(realNames);
        for (int i = 0; i < fillers; i++) all.add("filler" + String.format("%05d", i));
        java.util.Collections.shuffle(all, new java.util.Random(5));
        NamePool p = new NamePool(cfg, new java.util.Random(7));
        NamePool.LoadResult r = p.load(new java.io.ByteArrayInputStream(String.join("\n", all).getBytes(StandardCharsets.UTF_8)), "test pool");
        if (!r.accepted()) throw new AssertionError(r.message());
        return p;
    }
}
