package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class FictionalSkinRegistryTest {
    private static FictionalSkinRegistry registry(NicksConfig cfg, Path dir) {
        return new FictionalSkinRegistry(cfg, new AtomicJsonFile(dir.resolve("fictional_skins.json")),
                new SkinTextureCache(dir.resolve("skin_cache"), 64), dir.resolve("assets"));
    }

    @Test void everyFictionalPlayerGetsAUniqueSkinAndLook() throws Exception {
        FictionalSkinRegistry r = registry(fastConfig(), tempDir());
        Set<String> hashes = new HashSet<>(), keys = new HashSet<>();
        for (int i = 0; i < 150; i++) {
            var a = r.assign(UUID.randomUUID(), "Fake" + i, false);
            assertTrue(hashes.add(a.skin().hash()), "duplicate texture");
            assertTrue(keys.add(a.entry().visualKey()), "duplicate look");
        }
    }

    @Test void assignmentIsStableAcrossCallsAndRestarts() throws Exception {
        Path dir = tempDir();
        FictionalSkinRegistry r = registry(fastConfig(), dir);
        UUID u = UUID.randomUUID();
        var first = r.assign(u, "ToastBuilder", false);
        assertEquals(first.skin().hash(), r.assign(u, "ToastBuilder", false).skin().hash());
        r.flush();
        FictionalSkinRegistry reloaded = registry(fastConfig(), dir);
        var after = reloaded.assign(u, "ToastBuilder", false);
        assertEquals(first.skin().hash(), after.skin().hash());
        assertEquals(first.entry().model(), after.entry().model());
    }

    @Test void skinIsRebuiltFromSeedWhenCacheFileIsGone() throws Exception {
        Path dir = tempDir();
        FictionalSkinRegistry r = registry(fastConfig(), dir);
        UUID u = UUID.randomUUID();
        String hash = r.assign(u, "Nick", false).skin().hash();
        r.flush();
        try (var s = Files.list(dir.resolve("skin_cache"))) { for (Path p : (Iterable<Path>) s::iterator) Files.delete(p); }
        FictionalSkinRegistry reloaded = registry(fastConfig(), dir);
        assertEquals(hash, reloaded.load(reloaded.byPlayer(u).orElseThrow()).hash());
    }

    @Test void rerollGivesADifferentSkinAndFreesTheOldOne() throws Exception {
        FictionalSkinRegistry r = registry(fastConfig(), tempDir());
        UUID u = UUID.randomUUID();
        String h1 = r.assign(u, "Nick", false).skin().hash();
        String h2 = r.assign(u, "Nick", true).skin().hash();
        assertNotEquals(h1, h2);
        assertEquals(1, r.entries().size());
    }

    @Test void assetLibraryIsUsedFirstWithModelDetection() throws Exception {
        Path dir = tempDir();
        Files.createDirectories(dir.resolve("assets"));
        Files.write(dir.resolve("assets/knight.png"), FictionalSkinGenerator.generate(FictionalSkinGenerator.specFor(1, SkinModel.CLASSIC)).png());
        Files.write(dir.resolve("assets/mage.slim.png"), FictionalSkinGenerator.generate(FictionalSkinGenerator.specFor(2, SkinModel.SLIM)).png());
        Files.writeString(dir.resolve("assets/broken.png"), "garbage");
        NicksConfig cfg = fastConfig();
        FictionalSkinRegistry r = registry(cfg, dir);
        assertEquals(2, r.assetCount(), "broken asset skipped, valid ones loaded");
        var a = r.assign(UUID.randomUUID(), "A", false);
        var b = r.assign(UUID.randomUUID(), "B", false);
        assertEquals("asset", a.entry().kind());
        assertEquals("asset", b.entry().kind());
        assertNotEquals(a.skin().hash(), b.skin().hash());
        assertEquals(java.util.Set.of(SkinModel.CLASSIC, SkinModel.SLIM), java.util.Set.of(a.skin().model(), b.skin().model()));
    }

    @Test void exhaustedLibraryDoesNotCrashAndHonoursDuplicatePolicy() throws Exception {
        NicksConfig cfg = fastConfig();
        cfg.skin.enableProceduralGeneration = false;
        Path dir = tempDir();
        Files.createDirectories(dir.resolve("assets"));
        Files.write(dir.resolve("assets/only.png"), FictionalSkinGenerator.generate(FictionalSkinGenerator.specFor(1, SkinModel.CLASSIC)).png());
        FictionalSkinRegistry r = registry(cfg, dir);
        r.assign(UUID.randomUUID(), "A", false);
        assertThrows(SkinException.class, () -> r.assign(UUID.randomUUID(), "B", false));
        cfg.skin.allowDuplicateFictionalSkins = true;
        assertNotNull(r.assign(UUID.randomUUID(), "B", false));
    }
}
