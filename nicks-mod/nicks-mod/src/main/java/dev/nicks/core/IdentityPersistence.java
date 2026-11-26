package dev.nicks.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * identities.json storage with schema versioning, validation and debounced atomic saves.
 * A file written by a NEWER schema is loaded read-only and never overwritten.
 */
public final class IdentityPersistence implements AutoCloseable {
    public static final int SCHEMA = 1;

    private final AtomicJsonFile file;
    private final Supplier<Collection<PlayerIdentity>> snapshot;
    private final int delaySeconds;
    private final boolean autoSave;
    private final ScheduledThreadPoolExecutor scheduler;
    private ScheduledFuture<?> pending;
    private final Object flushLock = new Object();
    private volatile boolean readOnly;

    public IdentityPersistence(AtomicJsonFile file, Supplier<Collection<PlayerIdentity>> snapshot, NicksConfig cfg) {
        this.file = file;
        this.snapshot = snapshot;
        this.delaySeconds = cfg.storage.saveDelaySeconds;
        this.autoSave = cfg.storage.autoSave;
        this.scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "Nicks-Save");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); // no stale saves after close()
    }

    public Map<UUID, PlayerIdentity> load() {
        Map<UUID, PlayerIdentity> out = new LinkedHashMap<>();
        Map<String, Object> root = file.read();
        if (root == null) return out;
        long schema = Json.num(root, "schema", 1);
        if (schema > SCHEMA) {
            readOnly = true;
            NicksLog.error("identities.json uses schema " + schema + " (this build understands " + SCHEMA
                    + "). Loaded read-only; changes will NOT be saved.", null);
        }
        root = migrate(root, schema);
        for (Object o : Json.list(root, "identities")) {
            if (!(o instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            try {
                PlayerIdentity id = parseIdentity(m);
                out.put(id.playerUuid(), id);
            } catch (RuntimeException e) {
                NicksLog.warn("Skipping invalid identity entry: " + e.getMessage());
            }
        }
        return out;
    }

    /** Hook for future schema changes. */
    private static Map<String, Object> migrate(Map<String, Object> root, long from) {
        return root; // schema 1 is the first version
    }

    static PlayerIdentity parseIdentity(Map<String, Object> m) {
        UUID uuid = UUID.fromString(Json.str(m, "uuid", ""));
        String officialStr = Json.str(m, "officialProfileUuid", "");
        List<String> history = new ArrayList<>();
        for (Object h : Json.list(m, "nicknameHistory")) if (h instanceof String s) history.add(s);
        String nick = Json.str(m, "nickname", "");
        long now = System.currentTimeMillis();
        return new PlayerIdentity(uuid, Json.str(m, "originalUsername", ""), nick.isEmpty() ? null : nick,
                Json.bool(m, "active", false), IdentityType.parse(Json.str(m, "identityType", "")),
                Json.str(m, "skinSource", "original"), SkinModel.parse(Json.str(m, "skinModel", "")),
                Json.str(m, "skinHash", ""), Json.str(m, "fictionalSkinId", ""),
                officialStr.isEmpty() ? null : UUID.fromString(officialStr), Json.bool(m, "skinLocked", false),
                Json.num(m, "revision", 0), Json.num(m, "createdAt", now), Json.num(m, "lastUpdated", now), history);
    }

    static Map<String, Object> toMap(PlayerIdentity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", p.playerUuid().toString());
        m.put("originalUsername", p.originalUsername());
        m.put("nickname", p.nickname() == null ? "" : p.nickname());
        m.put("active", p.active());
        m.put("identityType", p.identityType().name());
        m.put("skinSource", p.skinSource());
        m.put("skinModel", p.skinModel().name());
        m.put("skinHash", p.skinHash());
        m.put("fictionalSkinId", p.fictionalSkinId());
        m.put("officialProfileUuid", p.officialProfileUuid() == null ? "" : p.officialProfileUuid().toString());
        m.put("skinLocked", p.skinLocked());
        m.put("revision", p.revision());
        m.put("createdAt", p.createdAt());
        m.put("lastUpdated", p.lastUpdated());
        m.put("nicknameHistory", p.nicknameHistory());
        return m;
    }

    /** Schedule a coalesced save. */
    public synchronized void markDirty() {
        if (!autoSave || readOnly) return;
        if (pending != null && !pending.isDone()) return;
        try {
            pending = scheduler.schedule(this::flushQuietly, delaySeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // shut down already; close() performed the final flush
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (IOException e) {
            NicksLog.error("Failed to save identities.json (will retry on next change)", e);
        }
    }

    /** Synchronous save (also used on shutdown). */
    public void flush() throws IOException {
        if (readOnly) return;
        synchronized (flushLock) { // snapshot + write are one step, so an older snapshot can never land after a newer one
            Collection<PlayerIdentity> all = snapshot.get();
            List<Object> list = new ArrayList<>();
            for (PlayerIdentity p : all) list.add(toMap(p));
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schema", SCHEMA);
            root.put("identities", list);
            file.write(root);
            NicksLog.debug("Identity file saved (" + list.size() + " identities)");
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try {
            flush();
        } catch (IOException e) {
            NicksLog.error("Final identity save failed", e);
        }
    }
}
