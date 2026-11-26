package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficialProfileResolverTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String URL = "http://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef";

    private static ProfileLookupResult run(OfficialProfileResolver r, String name) throws Exception {
        return r.lookup(name, false).get(10, TimeUnit.SECONDS);
    }

    private OfficialProfileResolver resolver(FakeHttp http) { return new OfficialProfileResolver(http, fastConfig()); }

    @Test void realAccountReturnsOfficialSkinUrlAndModel() throws Exception {
        FakeHttp http = new FakeHttp(mojang(n -> n.equals("notch") ? NOTCH : null, n -> URL, n -> "slim"));
        ProfileLookupResult r = run(resolver(http), "Notch");
        ProfileLookupResult.Real real = assertInstanceOf(r);
        assertEquals(NOTCH, real.profileUuid());
        assertEquals(URL, real.skinUrl());
        assertEquals(SkinModel.SLIM, real.model());
    }

    @Test void accountWithoutCustomSkinHasNullUrl() throws Exception {
        FakeHttp http = new FakeHttp(mojang(n -> n.equals("notch") ? NOTCH : null, n -> null, n -> null));
        ProfileLookupResult.Real real = assertInstanceOf(run(resolver(http), "Notch"));
        assertNull(real.skinUrl());
        assertEquals(SkinModel.CLASSIC, real.model());
    }

    @Test void confirmed404IsNotFoundAndIsCached() throws Exception {
        FakeHttp http = new FakeHttp(mojang(n -> null, n -> null, n -> null));
        OfficialProfileResolver r = resolver(http);
        assertTrue(run(r, "ToastBuilder") instanceof ProfileLookupResult.NotFound);
        int after = http.requests.get();
        assertTrue(run(r, "toastbuilder") instanceof ProfileLookupResult.NotFound);
        assertEquals(after, http.requests.get(), "negative result is cached, case-insensitively");
    }

    @Test void serviceFailuresAreNeverReportedAsNotFound() throws Exception {
        FakeHttp down = new FakeHttp(u -> resp(503, "unavailable"));
        assertTrue(run(resolver(down), "Notch") instanceof ProfileLookupResult.Unresolved);
        assertEquals(3, down.requests.get(), "retried up to the limit");

        FakeHttp io = new FakeHttp(u -> { throw new IOException("timeout"); });
        assertTrue(run(resolver(io), "Notch") instanceof ProfileLookupResult.Unresolved);

        FakeHttp limited = new FakeHttp(u -> resp(429, "{}"));
        assertTrue(run(resolver(limited), "Notch") instanceof ProfileLookupResult.Unresolved);

        FakeHttp junk = new FakeHttp(u -> resp(200, "<html>captive portal</html>"));
        assertTrue(run(resolver(junk), "Notch") instanceof ProfileLookupResult.Unresolved);

        FakeHttp odd404 = new FakeHttp(u -> resp(404, "{\"errorType\":\"SOMETHING_ELSE\"}"));
        assertTrue(run(resolver(odd404), "Notch") instanceof ProfileLookupResult.Unresolved);
    }

    @Test void unresolvedIsNotCachedSoNextCallRetries() throws Exception {
        List<Integer> script = new ArrayList<>(List.of(503, 503, 503));
        FakeHttp http = new FakeHttp(u -> {
            if (!script.isEmpty()) return resp(script.remove(0), "x");
            return mojang(n -> NOTCH, n -> URL, n -> null).handle(u);
        });
        OfficialProfileResolver r = resolver(http);
        assertTrue(run(r, "Notch") instanceof ProfileLookupResult.Unresolved);
        assertInstanceOf(run(r, "Notch"));
    }

    @Test void transientErrorThenSuccessRetries() throws Exception {
        int[] n = {0};
        FakeHttp http = new FakeHttp(u -> n[0]++ == 0 ? resp(500, "oops") : mojang(x -> NOTCH, x -> URL, x -> null).handle(u));
        assertInstanceOf(run(resolver(http), "Notch"));
    }

    @Test void concurrentLookupsOfSameNameShareOneRequest() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeHttp http = new FakeHttp(u -> {
            try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            return mojang(x -> NOTCH, x -> URL, x -> null).handle(u);
        });
        OfficialProfileResolver r = resolver(http);
        List<CompletableFuture<ProfileLookupResult>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) fs.add(r.lookup("Notch", false));
        gate.countDown();
        for (var f : fs) assertInstanceOf(f.get(10, TimeUnit.SECONDS));
        assertEquals(1, http.nameRequests.get());
    }

    @Test void invalidAccountNamesAreNotQueried() throws Exception {
        FakeHttp http = new FakeHttp(u -> resp(500, "should not be called"));
        assertTrue(run(resolver(http), "no spaces!") instanceof ProfileLookupResult.NotFound);
        assertEquals(0, http.requests.get());
    }

    private static ProfileLookupResult.Real assertInstanceOf(ProfileLookupResult r) {
        assertTrue(r instanceof ProfileLookupResult.Real, "expected Real but got " + r);
        return (ProfileLookupResult.Real) r;
    }
}
