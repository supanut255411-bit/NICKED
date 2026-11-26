package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The supply of skins and names must never run dry in practice. */
class InfiniteVarietyTest {
    @Test void thousandsOfFictionalPlayersEachGetADistinctTextureAndLook() throws Exception {
        Path dir = tempDir();
        FictionalSkinRegistry r = new FictionalSkinRegistry(fastConfig(), new AtomicJsonFile(dir.resolve("f.json")),
                new SkinTextureCache(dir.resolve("c"), 64), dir.resolve("assets"));
        Set<String> hashes = new HashSet<>(), keys = new HashSet<>();
        for (int i = 0; i < 2500; i++) {
            var a = r.assign(UUID.randomUUID(), "Fake" + i, false);
            assertTrue(hashes.add(a.skin().hash()), "duplicate texture at " + i);
            assertTrue(keys.add(a.entry().visualKey()), "duplicate look at " + i);
        }
        r.close();
    }

    @Test void designSpaceIsHugeAndColoursAreContinuous() {
        Set<String> keys = new HashSet<>(), hashes = new HashSet<>(), primaries = new HashSet<>();
        for (long seed = 0; seed < 6000; seed++) {
            FictionalSkinGenerator.Spec s = FictionalSkinGenerator.specFor(seed * 7919 + 13, SkinModel.CLASSIC);
            keys.add(s.visualKey());
            primaries.add(Integer.toHexString(s.primary()));
            if (seed < 1500) hashes.add(FictionalSkinGenerator.generate(s).hash());
        }
        assertTrue(keys.size() > 5950, "looks should almost never repeat, got " + keys.size() + " of 6000");
        assertEquals(1500, hashes.size());
        assertTrue(primaries.size() > 5900, "primary colours are drawn from a continuous range, got " + primaries.size());
    }

    @Test void nicknameSupplyDoesNotRunOut() {
        NicknameGenerator g = new NicknameGenerator(fastConfig(), new Random(7));
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 30_000; i++) {
            String n = g.next(x -> used.contains(x.toLowerCase())).orElse(null);
            assertNotNull(n, "ran out of names after " + i);
            assertTrue(Nicknames.isValidAccountName(n), n);
            assertTrue(used.add(n.toLowerCase()), "duplicate " + n);
        }
    }

    @Test void nameSupplySurvivesTinyCustomPoolsAndTightLengths() throws Exception {
        Path dir = tempDir();
        Files.writeString(dir.resolve("prefixes.txt"), "ab\n");
        Files.writeString(dir.resolve("suffixes.txt"), "cd\n");
        NicksConfig cfg = fastConfig();
        cfg.nickname.randomMinLength = 6;
        cfg.nickname.randomMaxLength = 10;
        NicknameGenerator g = new NicknameGenerator(cfg, new Random(11));
        g.loadPools(dir);
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String n = g.next(x -> used.contains(x.toLowerCase())).orElse(null);
            assertNotNull(n, "ran out after " + i);
            assertTrue(n.length() >= 6 && n.length() <= 10, n);
            used.add(n.toLowerCase());
        }
    }
}
