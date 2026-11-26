package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end behaviour of "nickname in, ready-to-apply skin out", with a scripted Mojang. */
class SkinPipelineTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID ALEX = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String NOTCH_URL = "http://textures.minecraft.net/texture/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String ALEX_URL = "http://textures.minecraft.net/texture/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static byte[] png(int h, int rgb) {
        BufferedImage i = new BufferedImage(64, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < 64; x++) i.setRGB(x, y, 0xFF000000 | rgb);
        return SkinImages.encodePng(i);
    }

    private static class Env {
        final NicksConfig cfg = fastConfig();
        final Path dir = tempDir();
        final FakeHttp http;
        final SkinPipeline pipeline;
        final FictionalSkinRegistry fictional;
        final SkinTextureCache cache;
        final OfficialProfileResolver resolver;

        Env(Handler profiles) {
            http = new FakeHttp(uri -> {
                String s = uri.toString();
                if (s.startsWith("https://textures.minecraft.net/texture/a")) return new HttpTransport.Response(200, png(32, 0x884422), java.util.Map.of());   // legacy 64x32
                if (s.startsWith("https://textures.minecraft.net/texture/b")) return new HttpTransport.Response(200, png(64, 0x2244AA), java.util.Map.of());
                return profiles.handle(uri);
            });
            cache = new SkinTextureCache(dir.resolve("cache"), 32);
            fictional = new FictionalSkinRegistry(cfg, new AtomicJsonFile(dir.resolve("f.json")), cache, dir.resolve("assets"));
            resolver = new OfficialProfileResolver(http, cfg);
            pipeline = new SkinPipeline(cfg, resolver, new SkinTextureDownloader(http, cfg), cache, fictional);
        }
    }

    private static Handler world() {
        return mojang(n -> n.equals("notch") ? NOTCH : n.equals("alexfan") ? ALEX : null,
                n -> n.equals("notch") ? NOTCH_URL : ALEX_URL, n -> n.equals("alexfan") ? "slim" : null);
    }

    @Test void realAccountGetsItsOfficialSkinWithTheRightModel() {
        Env e = new Env(world());
        var notch = (SkinPipeline.Resolved) e.pipeline.forNickname(UUID.randomUUID(), "Notch", false);
        assertEquals(IdentityType.REAL_ACCOUNT, notch.type());
        assertEquals(SkinModel.CLASSIC, notch.skin().model());
        assertEquals("official:Notch", notch.skinSource());
        assertEquals(NOTCH, notch.officialUuid());
        var alex = (SkinPipeline.Resolved) e.pipeline.forNickname(UUID.randomUUID(), "AlexFan", false);
        assertEquals(SkinModel.SLIM, alex.skin().model());
        assertNotEquals(notch.skin().hash(), alex.skin().hash());
        assertTrue(e.cache.get(alex.skin().hash(), SkinModel.SLIM).isPresent(), "cached for later sync");
        assertEquals(0, e.fictional.entries().size(), "no fictional skin is created for real accounts");
    }

    @Test void legacyOfficialSkinIsConvertedTo64x64() throws Exception {
        Env e = new Env(world());
        var notch = (SkinPipeline.Resolved) e.pipeline.forNickname(UUID.randomUUID(), "Notch", false);
        BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(notch.skin().png()));
        assertEquals(64, img.getHeight());
    }

    @Test void unknownNamesBecomeFictionalWithDifferentSkinsPerPlayerAndAreStable() {
        Env e = new Env(world());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var one = (SkinPipeline.Resolved) e.pipeline.forNickname(a, "ToastBuilder", false);
        var two = (SkinPipeline.Resolved) e.pipeline.forNickname(b, "ShadowWolf99", false);
        assertEquals(IdentityType.FICTIONAL, one.type());
        assertNotEquals(one.skin().hash(), two.skin().hash());
        var again = (SkinPipeline.Resolved) e.pipeline.forNickname(a, "ToastBuilder", false);
        assertEquals(one.skin().hash(), again.skin().hash(), "same player keeps the same fictional skin");
    }

    @Test void mojangOutageNeverTurnsARealAccountIntoAFictionalOne() {
        Env e = new Env(uri -> resp(503, "down"));
        SkinPipeline.Outcome o = e.pipeline.forNickname(UUID.randomUUID(), "Notch", false);
        SkinPipeline.Failed f = (SkinPipeline.Failed) o;
        assertTrue(f.transientError());
        assertEquals(0, e.fictional.entries().size(), "no fictional skin assigned during an outage");
    }

    @Test void brokenTextureDownloadFailsCleanly() {
        Env e = new Env(mojang(n -> NOTCH, n -> "http://textures.minecraft.net/texture/cccccccccccccccccccccccccccccccc", n -> null));
        SkinPipeline.Outcome o = e.pipeline.forNickname(UUID.randomUUID(), "Notch", false);
        assertTrue(o instanceof SkinPipeline.Failed);
    }

    @Test void accountWithoutCustomSkinUsesADefaultSkinNotAFictionalOne() {
        Env e = new Env(mojang(n -> NOTCH, n -> null, n -> null));
        var r = (SkinPipeline.Resolved) e.pipeline.forNickname(UUID.randomUUID(), "Notch", false);
        assertEquals(IdentityType.REAL_ACCOUNT, r.type());
        assertEquals(0, e.fictional.entries().size());
    }

    @Test void skinSourceMustBeARealAccount() {
        Env e = new Env(world());
        assertTrue(e.pipeline.forSkinSource("DefinitelyNotReal", false) instanceof SkinPipeline.Failed);
        assertTrue(e.pipeline.forSkinSource("Notch", false) instanceof SkinPipeline.Resolved);
    }

    @Test void freeFormNicknamesAreFictionalWithoutAnyNetworkCall() {
        Env e = new Env(uri -> { throw new AssertionError("no lookup expected"); });
        var r = (SkinPipeline.Resolved) e.pipeline.forNickname(UUID.randomUUID(), "Shadow Wolf", false);
        assertEquals(IdentityType.FICTIONAL, r.type());
        assertEquals(0, e.http.requests.get());
    }

    @Test void officialResolutionCanBeDisabled() {
        Env e = new Env(uri -> { throw new AssertionError("no lookup expected"); });
        e.cfg.skin.enableOfficialResolution = false;
        var r = (SkinPipeline.Resolved) e.pipeline.forNickname(UUID.randomUUID(), "Notch", false);
        assertEquals(IdentityType.FICTIONAL, r.type());
    }

    @Test void skinForRebuildsPixelsFromDiskCacheAfterRestart() {
        Env e = new Env(world());
        UUID u = UUID.randomUUID();
        var r = (SkinPipeline.Resolved) e.pipeline.forNickname(u, "Notch", false);
        PlayerIdentity id = PlayerIdentity.initial(u, "Real", 0).toBuilder().build();
        PlayerIdentity.Builder b = id.toBuilder();
        b.active = true; b.nickname = "Notch"; b.identityType = r.type(); b.skinHash = r.skin().hash(); b.skinModel = r.skin().model();
        SkinTextureCache fresh = new SkinTextureCache(e.dir.resolve("cache"), 32); // new process, same disk
        SkinPipeline p2 = new SkinPipeline(e.cfg, e.resolver, new SkinTextureDownloader(e.http, e.cfg), fresh, e.fictional);
        assertEquals(r.skin().hash(), p2.skinFor(b.build()).hash());
    }
}
