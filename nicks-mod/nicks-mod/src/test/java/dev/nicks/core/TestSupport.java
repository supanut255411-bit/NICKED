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
        String tex = skinUrl == null ? "{\"textures\":{}}" : "{\"textures\":{\"SKIN\":{\"url\":\"" + skinUrl + "\""
                + (model == null ? "" : ",\"metadata\":{\"model\":\"" + model + "\"}") + "}}}";
        return "{\"id\":\"" + undashed(id) + "\",\"name\":\"" + name + "\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + Base64.getEncoder().encodeToString(tex.getBytes(StandardCharsets.UTF_8)) + "\"}]}";
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
            String hex = s.substring(s.lastIndexOf('/') + 1).replace("?unsigned=true", "");
            for (String[] acct : new String[][] {{"notch", "Notch"}, {"alexfan", "AlexFan"}}) { // Mojang returns canonical capitalisation
                String n = acct[0];
                java.util.UUID id = accounts.apply(n);
                if (id != null && undashed(id).equals(hex)) return resp(200, sessionJson(id, acct[1], skinUrl.apply(n), model.apply(n)));
            }
            return resp(204, "");
        };
    }
}
