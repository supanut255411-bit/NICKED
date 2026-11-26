package dev.nicks.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Crash-safe JSON file: writes go to a temp file that is fsynced and atomically moved into place, the previous
 * version is kept as ".bak", and a corrupt main file is quarantined while the backup is loaded instead.
 */
public final class AtomicJsonFile {
    private final Path path;
    private final Path bak;
    private final Object lock = new Object();

    public AtomicJsonFile(Path path) {
        this.path = path;
        this.bak = path.resolveSibling(path.getFileName() + ".bak");
    }

    public Path path() { return path; }

    /** @return parsed object, or null when neither the file nor its backup exists / is readable. */
    public Map<String, Object> read() {
        synchronized (lock) {
            if (!Files.exists(path) && !Files.exists(bak)) return null;
            Map<String, Object> m = tryRead(path);
            if (m != null) return m;
            if (Files.exists(path)) {
                try {
                    Path q = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
                    Files.move(path, q, StandardCopyOption.REPLACE_EXISTING);
                    NicksLog.warn(path.getFileName() + " was unreadable; quarantined as " + q.getFileName());
                } catch (IOException e) {
                    NicksLog.warn("Could not quarantine corrupt file " + path, e);
                }
            }
            m = tryRead(bak);
            if (m != null) NicksLog.warn("Recovered " + path.getFileName() + " from backup");
            return m;
        }
    }

    private static Map<String, Object> tryRead(Path p) {
        try {
            if (!Files.exists(p)) return null;
            return Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void write(Map<String, Object> json) throws IOException {
        synchronized (lock) {
            if (Files.exists(path) && tryRead(path) != null) {
                Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            writeString(path, Json.write(json));
        }
    }

    /** Atomic write of arbitrary text (temp file + fsync + move). */
    public static void writeString(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + "." + System.nanoTime() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(StandardCharsets.UTF_8.encode(content));
            ch.force(true);
        }
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
