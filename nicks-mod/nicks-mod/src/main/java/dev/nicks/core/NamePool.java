package dev.nicks.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * The random-nickname database (the supplied one-million-username file). Loaded once and cached in a compact form
 * (one shared byte[] plus an offset index, about 20 MB for a million names, not a million String objects), in the
 * original file order. A reload only replaces the cached pool when the new file is valid; otherwise the previous valid
 * pool stays in use. There is deliberately no built-in fallback list.
 */
public final class NamePool {
    /** Outcome of a (re)load. */
    public record LoadResult(boolean accepted, int names, int invalid, int duplicates, String message) {}

    private record Data(byte[] bytes, int[] starts, byte[] lens, int count) {}

    private static final int MAX_FILE_BYTES = 128 * 1024 * 1024;

    private final NicksConfig cfg;
    private final Random rnd;
    private volatile Data data;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Set<String> recentSet = new HashSet<>();

    public NamePool(NicksConfig cfg, Random rnd) {
        this.cfg = cfg;
        this.rnd = rnd;
    }

    public boolean loaded() { return data != null; }

    public int size() { Data d = data; return d == null ? 0 : d.count(); }

    /** The i-th name in the original file order. */
    public String get(int index) {
        Data d = data;
        if (d == null || index < 0 || index >= d.count()) throw new IndexOutOfBoundsException(index);
        return new String(d.bytes(), d.starts()[index], d.lens()[index] & 0xFF, StandardCharsets.ISO_8859_1);
    }

    /**
     * Parse and validate; replaces the cached pool only if the file is acceptable. Lines must be 3-16 characters of
     * letters, digits or underscore; stray invalid lines and case-insensitive duplicates are skipped and reported;
     * a file that is mostly invalid (corrupted / binary) is rejected.
     */
    public synchronized LoadResult load(InputStream in, String label) {
        byte[] raw;
        try (in) {
            raw = in.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            return new LoadResult(false, 0, 0, 0, label + ": could not be read (" + e.getMessage() + ")");
        }
        if (raw.length > MAX_FILE_BYTES) return new LoadResult(false, 0, 0, 0, label + ": file is larger than 128 MB");

        int newlines = 0;
        for (byte b : raw) if (b == '\n') newlines++;
        int[] starts = new int[newlines + 1];
        byte[] lens = new byte[newlines + 1];
        long[] table = new long[Integer.highestOneBit(Math.max(8, newlines + 1) * 2 - 1) << 1]; // open-addressing hash set
        int count = 0, invalid = 0, duplicates = 0, lineStart = 0;
        for (int i = 0; i <= raw.length; i++) {
            if (i < raw.length && raw[i] != '\n') continue;
            int end = i;
            if (end > lineStart && raw[end - 1] == '\r') end--;
            int len = end - lineStart;
            boolean last = i == raw.length;
            if (len == 0) {
                if (!last) invalid++; // empty entry inside the file
            } else if (!validName(raw, lineStart, len)) {
                invalid++;
            } else if (!addHash(table, hashLower(raw, lineStart, len))) {
                duplicates++;
            } else {
                starts[count] = lineStart;
                lens[count] = (byte) len;
                count++;
            }
            lineStart = i + 1;
        }
        if (count == 0) return new LoadResult(false, 0, invalid, duplicates, label + ": contains no valid usernames");
        if (invalid > count / 100 + 10) {
            return new LoadResult(false, count, invalid, duplicates,
                    label + ": looks corrupted (" + invalid + " invalid lines out of " + (count + invalid) + ")");
        }
        Data d;
        if (invalid == 0 && duplicates == 0) {
            d = new Data(raw, java.util.Arrays.copyOf(starts, count), java.util.Arrays.copyOf(lens, count), count);
        } else { // compact copy without the skipped lines
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length);
            int[] ns = new int[count];
            for (int i = 0; i < count; i++) {
                ns[i] = out.size();
                out.write(raw, starts[i], lens[i] & 0xFF);
            }
            d = new Data(out.toByteArray(), ns, java.util.Arrays.copyOf(lens, count), count);
        }
        this.data = d;
        String msg = label + ": loaded " + count + " usernames" + (invalid > 0 ? ", skipped " + invalid + " invalid" : "")
                + (duplicates > 0 ? ", skipped " + duplicates + " duplicates" : "");
        return new LoadResult(true, count, invalid, duplicates, msg);
    }

    /**
     * A random name from the pool that is allowed by the filters: within the configured length, not offensive, not
     * recently handed out and not rejected by {@code taken}. Empty only if nothing suitable turned up.
     */
    public synchronized Optional<String> pick(Predicate<String> taken) {
        Data d = data;
        if (d == null) return Optional.empty();
        for (int i = 0; i < 4000; i++) {
            String c = get(rnd.nextInt(d.count()));
            if (c.length() < cfg.nickname.randomMinLength || c.length() > cfg.nickname.randomMaxLength) continue;
            String key = Nicknames.key(c);
            if (banned(key) || recentSet.contains(key) || taken.test(c)) continue;
            recent.addLast(key);
            recentSet.add(key);
            while (recent.size() > Math.max(1, cfg.nickname.recentRandomMemory)) recentSet.remove(recent.removeFirst());
            return Optional.of(c);
        }
        return Optional.empty();
    }

    /** {@code k} distinct random names (used to probe Mojang for real accounts; not subject to the recent filter). */
    public synchronized List<String> sample(int k) {
        Data d = data;
        List<String> out = new ArrayList<>();
        if (d == null) return out;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < k * 4 && out.size() < k; i++) {
            int idx = rnd.nextInt(d.count());
            if (seen.add(idx)) out.add(get(idx));
        }
        return out;
    }

    private boolean banned(String key) {
        for (String f : cfg.nickname.bannedFragments) if (!f.isBlank() && key.contains(f.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean validName(byte[] b, int off, int len) {
        if (len < 3 || len > 16) return false;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static long hashLower(byte[] b, int off, int len) {
        long h = 0xcbf29ce484222325L;
        for (int i = off; i < off + len; i++) {
            int c = b[i];
            if (c >= 'A' && c <= 'Z') c += 32;
            h ^= c;
            h *= 0x100000001b3L;
        }
        return h == 0 ? 1 : h;
    }

    private static boolean addHash(long[] table, long h) {
        int mask = table.length - 1;
        int i = (int) (h ^ (h >>> 32)) & mask;
        while (table[i] != 0) {
            if (table[i] == h) return false;
            i = (i + 1) & mask;
        }
        table[i] = h;
        return true;
    }
}
