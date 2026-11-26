package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.net.URI;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class SkinTextureDownloaderTest {
    private static final String GOOD = "http://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef";

    private static byte[] skinPng(int h) {
        BufferedImage i = new BufferedImage(64, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < 64; x++) i.setRGB(x, y, 0xFF778899);
        return SkinImages.encodePng(i);
    }

    @Test void downloadsAndUpgradesHttpToHttps() throws Exception {
        String[] seen = new String[1];
        FakeHttp http = new FakeHttp(u -> { seen[0] = u.toString(); return new HttpTransport.Response(200, skinPng(64), java.util.Map.of()); });
        SkinData d = new SkinTextureDownloader(http, fastConfig()).download(GOOD, SkinModel.SLIM);
        assertTrue(seen[0].startsWith("https://textures.minecraft.net/"));
        assertEquals(SkinModel.SLIM, d.model());
    }

    @Test void legacy64x32SkinsAreAccepted() throws Exception {
        FakeHttp http = new FakeHttp(u -> new HttpTransport.Response(200, skinPng(32), java.util.Map.of()));
        assertNotNull(new SkinTextureDownloader(http, fastConfig()).download(GOOD, SkinModel.CLASSIC));
    }

    @Test void securityPolicyRejectsUntrustedUrls() {
        FakeHttp http = new FakeHttp(u -> { throw new AssertionError("must not fetch " + u); });
        SkinTextureDownloader d = new SkinTextureDownloader(http, fastConfig());
        for (String bad : new String[] {
                "https://evil.example.com/texture/0123456789abcdef0123456789abcdef",
                "https://textures.minecraft.net.evil.com/texture/0123456789abcdef0123456789abcdef",
                "https://user@textures.minecraft.net/texture/0123456789abcdef0123456789abcdef",
                "https://textures.minecraft.net:8443/texture/0123456789abcdef0123456789abcdef",
                "https://textures.minecraft.net/../etc/passwd",
                "https://textures.minecraft.net/texture/zz",
                "file:///etc/passwd", "ftp://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef"}) {
            assertThrows(SkinException.class, () -> d.download(bad, SkinModel.CLASSIC), bad);
        }
    }

    @Test void rejectsServerErrorsAndGarbage() {
        assertThrows(SkinException.class, () -> new SkinTextureDownloader(
                new FakeHttp(u -> resp(404, "nope")), fastConfig()).download(GOOD, SkinModel.CLASSIC));
        assertThrows(SkinException.class, () -> new SkinTextureDownloader(
                new FakeHttp(u -> resp(200, "GIF89a not a png")), fastConfig()).download(GOOD, SkinModel.CLASSIC));
    }
}
