package dev.nicks.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Minimal HTTP GET abstraction so profile/texture code can be tested without a network. */
public interface HttpTransport {
    record Response(int status, byte[] body, Map<String, String> headers) {}

    Response get(URI uri, Duration timeout, int maxBytes) throws IOException, InterruptedException;

    /** JDK HttpClient implementation: no redirects, bounded body, short timeouts. Never called on the server tick. */
    final class Jdk implements HttpTransport {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        @Override
        public Response get(URI uri, Duration timeout, int maxBytes) throws IOException, InterruptedException {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("User-Agent", "NicksMod/1.0 (+Fabric)").header("Accept", "*/*").GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                byte[] body = in.readNBytes(maxBytes + 1);
                if (body.length > maxBytes) throw new IOException("Response larger than " + maxBytes + " bytes");
                Map<String, String> headers = new HashMap<>();
                resp.headers().map().forEach((k, v) -> {
                    if (!v.isEmpty()) headers.put(k.toLowerCase(Locale.ROOT), v.get(0));
                });
                return new Response(resp.statusCode(), body, headers);
            }
        }
    }
}
