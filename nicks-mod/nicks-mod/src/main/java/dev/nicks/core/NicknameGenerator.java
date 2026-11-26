package dev.nicks.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Readable casual Minecraft-style random names (dirtboi7, toast3r, p1xelcat ...). Built from word pools, never
 * random character soup. Pools can be replaced via config/nicks/nickname_pools/ (names.txt, prefixes.txt,
 * suffixes.txt, or any other *.txt = ready-made names).
 */
public final class NicknameGenerator {
    private static final String[] PREFIXES = {"dirt", "fish", "toast", "milk", "pixel", "cobble", "creeper", "redstone",
            "torch", "lava", "obsidian", "pickaxe", "wheat", "bread", "cactus", "sheep", "cow", "pig", "slime", "ender",
            "bedrock", "gravel", "flint", "coal", "iron", "gold", "diamond", "emerald", "lapis", "glow", "moss", "fern",
            "birch", "oak", "acacia", "mango", "cookie", "waffle", "pancake", "noodle", "pickle", "potato", "carrot",
            "melon", "pumpkin", "honey", "bee", "fox", "wolf", "panda", "parrot", "turtle", "axolotl", "goat", "frog",
            "owl", "moth", "crab", "kelp", "coral", "reef", "cave", "ridge", "storm", "frost", "ember", "dusk", "dawn",
            "lunar", "solar", "comet", "orbit", "rocket", "turbo", "nova", "echo", "zen", "lofi"};
    private static final String[] SUFFIXES = {"boi", "man", "cat", "kid", "guy", "bro", "dude", "dog", "pup", "bug", "bot",
            "fan", "pro", "gamer", "rider", "miner", "crafter", "builder", "walker", "runner", "hunter", "smith", "wizard",
            "knight", "ninja", "sloth", "bean", "nugget", "buddy", "pal"};

    private final NicksConfig cfg;
    private final Random rnd;
    private String[] prefixes = PREFIXES;
    private String[] suffixes = SUFFIXES;
    private List<String> ready = List.of();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Set<String> recentSet = new HashSet<>();

    public NicknameGenerator(NicksConfig cfg, Random rnd) {
        this.cfg = cfg;
        this.rnd = rnd;
    }

    /** (Re)load pool files; invalid lines are ignored. */
    public synchronized void loadPools(Path dir) {
        prefixes = PREFIXES;
        suffixes = SUFFIXES;
        List<String> names = new ArrayList<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.txt")) {
                for (Path p : ds) {
                    List<String> lines = new ArrayList<>();
                    for (String l : Files.readAllLines(p)) {
                        String t = l.trim();
                        if (Nicknames.isValidAccountName(t) || (t.length() >= 2 && t.matches("[A-Za-z0-9_]+"))) lines.add(t);
                    }
                    String f = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (f.equals("prefixes.txt") && !lines.isEmpty()) prefixes = lines.toArray(new String[0]);
                    else if (f.equals("suffixes.txt") && !lines.isEmpty()) suffixes = lines.toArray(new String[0]);
                    else names.addAll(lines);
                }
            } catch (IOException e) {
                NicksLog.warn("Could not read nickname pools: " + e.getMessage());
            }
        }
        ready = names;
    }

    private static final String[] ONSET = {"b", "c", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "r", "s", "t", "v",
            "w", "z", "br", "cr", "dr", "fl", "gr", "kr", "pl", "sk", "sl", "sn", "st", "tr", "th", "sh", "ch"};
    private static final String[] VOWEL = {"a", "e", "i", "o", "u", "a", "e", "i", "o", "u", "ai", "ea", "oo", "ou", "ie"};
    private static final String[] CODA = {"", "", "", "", "n", "r", "l", "x", "k", "m", "s", "t"};

    /**
     * A fresh name that satisfies length/safety rules and is neither taken nor recently generated. Never runs out:
     * word-pool names come first, then pronounceable generated words (consonant-vowel syllables) with random digits,
     * so the supply is bounded only by Minecraft's own 16-character account-name limit.
     */
    public synchronized Optional<String> next(Predicate<String> taken) {
        int min = cfg.nickname.randomMinLength, max = cfg.nickname.randomMaxLength;
        for (int i = 0; i < 3000; i++) {
            String c;
            if (i < 300) c = !ready.isEmpty() && rnd.nextInt(2) == 0 ? ready.get(rnd.nextInt(ready.size())) : compose();
            else c = escalate(min, max);
            if (c.length() < min || c.length() > max || !Nicknames.isValidAccountName(c)) continue;
            String key = Nicknames.key(c);
            if (banned(key) || recentSet.contains(key) || taken.test(c)) continue;
            remember(key);
            return Optional.of(c);
        }
        return Optional.empty();
    }

    private String compose() {
        String a = prefixes[rnd.nextInt(prefixes.length)], b = suffixes[rnd.nextInt(suffixes.length)];
        return switch (rnd.nextInt(9)) {
            case 0 -> a + b;
            case 1 -> a + b + (1 + rnd.nextInt(99));
            case 2 -> leet(a) + b;
            case 3 -> a + rnd.nextInt(1000);
            case 4 -> a + leet(b);
            case 5 -> syllables(2 + rnd.nextInt(2));
            case 6 -> syllables(2 + rnd.nextInt(2)) + rnd.nextInt(100);
            case 7 -> a + syllables(1) + (rnd.nextInt(3) == 0 ? String.valueOf(rnd.nextInt(10)) : "");
            default -> leet(syllables(2)) + b;
        };
    }

    /** Pronounceable made-up word, e.g. "zorimak", "kelu", "brathoon". */
    private String syllables(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(ONSET[rnd.nextInt(ONSET.length)]).append(VOWEL[rnd.nextInt(VOWEL.length)]);
            if (i == count - 1 || rnd.nextInt(3) == 0) sb.append(CODA[rnd.nextInt(CODA.length)]);
        }
        return sb.toString();
    }

    /** Used once the easy pool is saturated: random-length pronounceable word padded with random digits. */
    private String escalate(int min, int max) {
        int target = min + rnd.nextInt(max - min + 1);
        StringBuilder sb = new StringBuilder(rnd.nextBoolean() ? syllables(2 + rnd.nextInt(3))
                : prefixes[rnd.nextInt(prefixes.length)] + suffixes[rnd.nextInt(suffixes.length)]);
        while (sb.length() < target && sb.length() < max) {
            sb.append(rnd.nextInt(3) == 0 ? String.valueOf(rnd.nextInt(10)) : syllables(1));
        }
        if (sb.length() > target) sb.setLength(Math.max(min, target));
        return sb.toString();
    }

    /** Swap one letter for a look-alike digit (toaster -> toast3r). */
    private String leet(String s) {
        List<Integer> spots = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) if ("aeio".indexOf(s.charAt(i)) >= 0 && i > 0) spots.add(i);
        if (spots.isEmpty()) return s;
        int i = spots.get(rnd.nextInt(spots.size()));
        char r = switch (s.charAt(i)) { case 'a' -> '4'; case 'e' -> '3'; case 'i' -> '1'; default -> '0'; };
        return s.substring(0, i) + r + s.substring(i + 1);
    }

    private boolean banned(String key) {
        for (String f : cfg.nickname.bannedFragments) if (!f.isBlank() && key.contains(f.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private void remember(String key) {
        recent.addLast(key);
        recentSet.add(key);
        while (recent.size() > Math.max(1, cfg.nickname.recentRandomMemory)) recentSet.remove(recent.removeFirst());
    }
}
