package dev.nicks.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Content-addressed skin cache: bounded in-memory LRU backed by skin_cache/&lt;hash&gt;.png. */
public final class SkinTextureCache {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Path dir;
    private final Map<String, byte[]> memory;

    public SkinTextureCache(Path dir, int maxEntries) {
        this.dir = dir;
        this.memory = new LinkedHashMap<>(32, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > maxEntries; }
        };
    }

    public static boolean validHash(String h) { return h != null && HASH.matcher(h).matches(); }

    public synchronized void put(SkinData s) {
        if (!validHash(s.hash())) return;
        memory.put(s.hash(), s.png());
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve(s.hash() + ".png");
            if (!Files.exists(f) || Files.size(f) == 0) {
                Path tmp = dir.resolve(s.hash() + ".png.tmp");
                Files.write(tmp, s.png());
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            NicksLog.warn("Could not persist skin " + s.hash().substring(0, 8) + ": " + e.getMessage());
        }
    }

    public synchronized Optional<SkinData> get(String hash, SkinModel model) {
        if (!validHash(hash)) return Optional.empty();
        byte[] png = memory.get(hash);
        if (png == null) {
            try {
                Path f = dir.resolve(hash + ".png");
                if (!Files.exists(f)) return Optional.empty();
                png = Files.readAllBytes(f);
                if (png.length == 0) return Optional.empty();
                memory.put(hash, png);
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.of(new SkinData(hash, model, png));
    }

    /** Delete cached files that no identity references and that are older than the given age. */
    public synchronized int prune(Set<String> referenced, long olderThanMillis) {
        int removed = 0;
        if (!Files.isDirectory(dir)) return 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.png")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                String hash = name.substring(0, name.length() - 4);
                if (referenced.contains(hash)) continue;
                if (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() < olderThanMillis) continue;
                Files.deleteIfExists(p);
                memory.remove(hash);
                removed++;
            }
        } catch (IOException e) {
            NicksLog.warn("Skin cache prune failed: " + e.getMessage());
        }
        return removed;
    }
}
