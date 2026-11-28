package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NamePoolTest {
    private static final String BUNDLED = "/nicks/nicks_1_million_usernames.txt";

    private static NamePool.LoadResult load(NamePool p, String text) {
        return p.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "test");
    }

    @Test void bundledMillionNameFileLoadsCompletelyInOriginalOrder() throws Exception {
        NamePool pool = new NamePool(fastConfig(), new Random(1));
        long t0 = System.nanoTime();
        NamePool.LoadResult r = pool.load(NamePoolTest.class.getResourceAsStream(BUNDLED), "bundled");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.accepted(), r.message());
        assertEquals(1_000_000, r.names());
        assertEquals(0, r.invalid());
        assertEquals(0, r.duplicates());
        assertEquals(1_000_000, pool.size());
        assertTrue(ms < 5000, "loading one million names took " + ms + " ms");
        assertEquals("sunnysandwich_8", pool.get(0));
        // every single entry matches the file line by line: nothing sorted, reordered or dropped
        try (InputStream in = NamePoolTest.class.getResourceAsStream(BUNDLED);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            int i = 0;
            while ((line = br.readLine()) != null) {
                if (!line.equals(pool.get(i))) throw new AssertionError("entry " + i + " differs: " + line + " vs " + pool.get(i));
                i++;
            }
            assertEquals(1_000_000, i);
        }
    }

    @Test void randomPicksComeFromThePoolAndRespectTakenAndRecentFilters() throws Exception {
        NicksConfig cfg = fastConfig();
        cfg.nickname.recentRandomMemory = 200;
        NamePool pool = new NamePool(cfg, new Random(2));
        assertTrue(pool.load(NamePoolTest.class.getResourceAsStream(BUNDLED), "bundled").accepted());
        Set<String> inPool = new HashSet<>();
        for (int i = 0; i < pool.size(); i++) inPool.add(pool.get(i).toLowerCase());
        Set<String> used = new HashSet<>();
        List<String> seq = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            String n = pool.pick(x -> used.contains(x.toLowerCase())).orElseThrow();
            assertTrue(inPool.contains(n.toLowerCase()), "not from the pool: " + n);
            assertTrue(used.add(n.toLowerCase()), "handed out twice: " + n);
            seq.add(n);
        }
        NamePool tiny = new NamePool(cfg, new Random(3));
        assertTrue(load(tiny, "aaa\nbbb\nccc\n").accepted());
        assertTrue(tiny.pick(x -> x.equals("aaa") || x.equals("bbb")).orElseThrow().equals("ccc"));
        assertTrue(tiny.pick(x -> true).isEmpty());
    }

    @Test void recentNamesAreAvoided() {
        NicksConfig cfg = fastConfig();
        cfg.nickname.recentRandomMemory = 3;
        NamePool p = new NamePool(cfg, new Random(4));
        assertTrue(load(p, "aaa\nbbb\nccc\nddd\neee\n").accepted());
        List<String> picks = new ArrayList<>();
        for (int i = 0; i < 40; i++) picks.add(p.pick(x -> false).orElseThrow());
        for (int i = 3; i < picks.size(); i++) {
            for (int j = i - 3; j < i; j++) assertNotEquals(picks.get(j), picks.get(i));
        }
    }

    @Test void invalidLinesAndDuplicatesAreSkippedAndOrderIsKept() {
        NamePool p = new NamePool(fastConfig(), new Random(1));
        NamePool.LoadResult r = load(p, "abc\r\nABC\nde\nvalid_one\n\nfoo bar\nxyz\n");
        assertTrue(r.accepted(), r.message());
        assertEquals(3, r.names());
        assertEquals(1, r.duplicates());
        assertEquals(3, r.invalid());
        assertEquals("abc", p.get(0));
        assertEquals("valid_one", p.get(1));
        assertEquals("xyz", p.get(2));
    }

    @Test void corruptedOrEmptyFilesAreRejectedAndThePreviousPoolIsKept() {
        NamePool p = new NamePool(fastConfig(), new Random(1));
        assertTrue(load(p, "alpha\nbravo\ncharlie\n").accepted());
        assertFalse(load(p, "").accepted());
        assertFalse(load(p, "\n\n\n").accepted());
        StringBuilder junk = new StringBuilder();
        for (int i = 0; i < 500; i++) junk.append("%%%not a name ").append(i).append('\n');
        NamePool.LoadResult bad = load(p, junk.toString());
        assertFalse(bad.accepted(), bad.message());
        assertEquals(3, p.size());
        assertEquals("alpha", p.get(0));
        assertEquals("charlie", p.get(2));
        assertFalse(p.pick(x -> false).isEmpty(), "still serving the previous valid pool");
    }

    @Test void thereIsNoHiddenFallbackList() {
        NamePool p = new NamePool(fastConfig(), new Random(1));
        assertFalse(p.loaded());
        assertTrue(p.pick(x -> false).isEmpty());
        assertTrue(p.sample(10).isEmpty());
    }

    @Test void offensiveFragmentsAreNeverHandedOut() {
        NicksConfig cfg = fastConfig();
        cfg.nickname.recentRandomMemory = 1; // two clean names, so only the last one handed out is held back
        NamePool p = new NamePool(cfg, new Random(1));
        assertTrue(load(p, "nazi_fan\nsweetpie\nporncat\ncleanname\n").accepted());
        for (int i = 0; i < 200; i++) {
            String n = p.pick(x -> false).orElseThrow();
            assertTrue(n.equals("sweetpie") || n.equals("cleanname"), n);
        }
    }

    @Test void sampleGivesDistinctNames() {
        NamePool p = pool(fastConfig(), List.of(), 500);
        List<String> s = p.sample(10);
        assertEquals(10, s.size());
        assertEquals(10, new HashSet<>(s).size());
    }
}
