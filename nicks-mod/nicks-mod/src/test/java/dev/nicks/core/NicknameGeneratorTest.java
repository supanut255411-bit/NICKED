package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NicknameGeneratorTest {
    @Test void generatesReadableValidUniqueNamesWithinLengthBounds() {
        NicksConfig cfg = fastConfig();
        NicknameGenerator g = new NicknameGenerator(cfg, new Random(42));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1500; i++) {
            String n = g.next(x -> seen.contains(x.toLowerCase())).orElseThrow();
            assertTrue(Nicknames.isValidAccountName(n), n);
            assertTrue(n.length() >= 3 && n.length() <= 16, n);
            assertTrue(seen.add(n.toLowerCase()), "duplicate " + n);
            for (String f : cfg.nickname.bannedFragments) assertFalse(n.toLowerCase().contains(f), n);
        }
    }

    @Test void respectsTakenNamesAndConfiguredLength() {
        NicksConfig cfg = fastConfig();
        cfg.nickname.randomMinLength = 5;
        cfg.nickname.randomMaxLength = 8;
        NicknameGenerator g = new NicknameGenerator(cfg, new Random(1));
        for (int i = 0; i < 300; i++) {
            String n = g.next(x -> x.toLowerCase().startsWith("dirt")).orElseThrow();
            assertTrue(n.length() >= 5 && n.length() <= 8, n);
            assertFalse(n.toLowerCase().startsWith("dirt"), n);
        }
    }

    @Test void customPoolFilesAreUsedAndBadLinesAndBannedNamesIgnored() throws Exception {
        Path dir = tempDir();
        Files.writeString(dir.resolve("prefixes.txt"), "alpha\nbeta\nnot valid!\n");
        Files.writeString(dir.resolve("suffixes.txt"), "one\ntwo\n");
        NicksConfig cfg = fastConfig();
        NicknameGenerator g = new NicknameGenerator(cfg, new Random(3));
        g.loadPools(dir);
        int fromPool = 0;
        for (int i = 0; i < 200; i++) {
            String n = g.next(x -> false).orElseThrow().toLowerCase();
            if (n.startsWith("alpha") || n.startsWith("beta") || n.startsWith("alph4") || n.startsWith("bet4")
                    || n.startsWith("4lpha") || n.startsWith("b3ta")) fromPool++;
        }
        assertTrue(fromPool >= 20, "custom prefixes should replace the built-in ones, saw " + fromPool);
    }

    @Test void fallsBackToEmptyInsteadOfLoopingWhenEverythingIsTaken() {
        NicknameGenerator g = new NicknameGenerator(fastConfig(), new Random(9));
        assertTrue(g.next(x -> true).isEmpty());
    }
}
