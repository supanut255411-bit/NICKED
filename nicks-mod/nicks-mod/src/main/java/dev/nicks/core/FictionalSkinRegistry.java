package dev.nicks.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Permanent database of fictional skin assignments (fictional_skins.json) plus the admin asset library.
 * Each fictional skin has an id, texture hash, model, owner UUID and nickname, and is only handed out once unless
 * duplicates are explicitly allowed. Procedural skins are reproducible from their seed.
 */
public final class FictionalSkinRegistry implements AutoCloseable {
    public static final int SCHEMA = 1;

    public record Entry(String id, String kind, long seed, String assetFile, SkinModel model, String hash,
                        UUID playerUuid, String nickname, long createdAt, String visualKey) {}

    public record Assignment(Entry entry, SkinData skin) {}

    private record Asset(String file, SkinData skin) {}

    private final NicksConfig cfg;
    private final AtomicJsonFile file;
    private final SkinTextureCache cache;
    private final Path assetDir;
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    private final Map<UUID, Entry> byPlayer = new LinkedHashMap<>();
    private final Random random = new Random();
    private List<Asset> assets = List.of();
    private final java.util.concurrent.ScheduledThreadPoolExecutor saver = newSaver();
    private java.util.concurrent.ScheduledFuture<?> pendingSave;
    private final Object flushLock = new Object();

    public FictionalSkinRegistry(NicksConfig cfg, AtomicJsonFile file, SkinTextureCache cache, Path assetDir) {
        this.cfg = cfg;
        this.file = file;
        this.cache = cache;
        this.assetDir = assetDir;
        load();
        reloadAssets();
    }

    // ---- public API ------------------------------------------------------

    public synchronized Optional<Entry> byPlayer(UUID uuid) { return Optional.ofNullable(byPlayer.get(uuid)); }

    public synchronized Collection<Entry> entries() { return new ArrayList<>(byId.values()); }

    public synchronized int assetCount() { return assets.size(); }

    /** Existing assignment for this player (stable across restarts) or a freshly chosen unused skin. */
    public synchronized Assignment assign(UUID player, String nickname, boolean forceNew) throws SkinException {
        Entry existing = byPlayer.get(player);
        String avoidHash = null;
        if (existing != null && !forceNew) {
            SkinData d = load(existing);
            if (d != null) {
                if (!existing.nickname().equals(nickname)) store(copyWithNickname(existing, nickname));
                return new Assignment(byId.get(existing.id()), d);
            }
        }
        if (existing != null) {
            avoidHash = existing.hash();
            remove(existing); // reroll (or unreadable entry): free the old assignment
        }

        Set<String> usedHashes = new HashSet<>();
        Set<String> usedKeys = new HashSet<>();
        for (Entry e : byId.values()) {
            usedHashes.add(e.hash());
            if (!e.visualKey().isEmpty()) usedKeys.add(e.visualKey());
        }
        if (avoidHash != null) usedHashes.add(avoidHash);
        boolean dup = cfg.skin.allowDuplicateFictionalSkins;

        // 1. admin asset library
        if (cfg.skin.preferAssetLibrary) {
            List<Asset> free = new ArrayList<>();
            for (Asset a : assets) if (!usedHashes.contains(a.skin().hash())) free.add(a);
            if (!free.isEmpty()) return createFromAsset(player, nickname, free.get(random.nextInt(free.size())));
        }
        // 2. procedural generator: prefer a new look, then at least a new texture
        if (cfg.skin.enableProceduralGeneration) {
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < 400; i++) {
                    long seed = random.nextLong();
                    SkinModel model = random.nextBoolean() ? SkinModel.SLIM : SkinModel.CLASSIC;
                    FictionalSkinGenerator.Spec spec = FictionalSkinGenerator.specFor(seed, model);
                    if (pass == 0 && usedKeys.contains(spec.visualKey())) continue;
                    SkinData d = FictionalSkinGenerator.generate(spec);
                    if (usedHashes.contains(d.hash()) && !dup) continue;
                    return createProcedural(player, nickname, seed, spec, d);
                }
            }
        }
        // 3. leftovers from the library
        if (!cfg.skin.preferAssetLibrary || dup) {
            List<Asset> pool = new ArrayList<>();
            for (Asset a : assets) if (dup || !usedHashes.contains(a.skin().hash())) pool.add(a);
            if (!pool.isEmpty()) return createFromAsset(player, nickname, pool.get(random.nextInt(pool.size())));
        }
        throw new SkinException("No unused fictional skins are available (add PNGs to the skin asset folder, "
                + "enable procedural generation, or allow duplicate fictional skins)");
    }

    /** Pixel data for an entry: cache first, then regenerate (procedural) or re-read (asset). */
    public synchronized SkinData load(Entry e) {
        Optional<SkinData> cached = cache.get(e.hash(), e.model());
        if (cached.isPresent()) return cached.get();
        SkinData d = null;
        try {
            if (e.kind().equals("procedural")) {
                d = FictionalSkinGenerator.generate(FictionalSkinGenerator.specFor(e.seed(), e.model()));
            } else {
                for (Asset a : assets) if (a.file().equals(e.assetFile())) d = a.skin();
            }
        } catch (RuntimeException ex) {
            NicksLog.warn("Could not rebuild fictional skin " + e.id() + ": " + ex.getMessage());
        }
        if (d == null) return null;
        if (!d.hash().equals(e.hash())) {
            NicksLog.warn("Fictional skin " + e.id() + " regenerated with a different hash (generator changed?); updating record");
            e = new Entry(e.id(), e.kind(), e.seed(), e.assetFile(), e.model(), d.hash(), e.playerUuid(), e.nickname(), e.createdAt(), e.visualKey());
            store(e);
        }
        cache.put(d);
        return d;
    }

    public synchronized void reloadAssets() {
        List<Asset> out = new ArrayList<>();
        if (assetDir != null && Files.isDirectory(assetDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(assetDir, "*.png")) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    try {
                        if (Files.size(p) > cfg.skin.maxSkinBytes) throw new SkinException("file too large");
                        var raw = SkinImages.decode(Files.readAllBytes(p), cfg.skin.maxSkinBytes);
                        SkinModel model = name.toLowerCase().endsWith(".slim.png") || SkinImages.looksSlim(raw)
                                ? SkinModel.SLIM : SkinModel.CLASSIC;
                        out.add(new Asset(name, SkinImages.toSkinData(SkinImages.normalize(raw), model)));
                    } catch (SkinException | IOException | RuntimeException e) {
                        NicksLog.warn("Skipping skin asset " + name + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                NicksLog.warn("Could not scan skin asset folder: " + e.getMessage());
            }
        }
        Collections.sort(out, (a, b) -> a.file().compareTo(b.file()));
        assets = out;
    }

    // ---- internals -------------------------------------------------------

    private Assignment createProcedural(UUID player, String nick, long seed, FictionalSkinGenerator.Spec spec, SkinData d) {
        Entry e = new Entry("p-" + Long.toHexString(seed), "procedural", seed, "", spec.model(), d.hash(), player,
                nick, System.currentTimeMillis(), spec.visualKey());
        cache.put(d);
        store(e);
        return new Assignment(e, d);
    }

    private Assignment createFromAsset(UUID player, String nick, Asset a) {
        String id = "a-" + a.file().replaceAll("[^A-Za-z0-9._-]", "_");
        // an asset may be reused under duplicate mode: disambiguate id by player
        if (byId.containsKey(id)) id = id + "-" + player.toString().substring(0, 8);
        Entry e = new Entry(id, "asset", 0, a.file(), a.skin().model(), a.skin().hash(), player, nick,
                System.currentTimeMillis(), "asset/" + a.file());
        cache.put(a.skin());
        store(e);
        return new Assignment(e, a.skin());
    }

    private Entry copyWithNickname(Entry e, String nick) {
        return new Entry(e.id(), e.kind(), e.seed(), e.assetFile(), e.model(), e.hash(), e.playerUuid(), nick, e.createdAt(), e.visualKey());
    }

    private void store(Entry e) {
        byId.put(e.id(), e);
        byPlayer.put(e.playerUuid(), e);
        save();
    }

    private void remove(Entry e) {
        byId.remove(e.id());
        byPlayer.remove(e.playerUuid());
        save();
    }

    private void load() {
        Map<String, Object> root = file.read();
        if (root == null) return;
        if (Json.num(root, "schema", 1) > SCHEMA) {
            NicksLog.error("fictional_skins.json uses a newer schema; ignoring it to avoid damaging data", null);
            return;
        }
        for (Object o : Json.list(root, "skins")) {
            if (!(o instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            try {
                Entry e = new Entry(Json.str(m, "id", ""), Json.str(m, "kind", "procedural"), Json.num(m, "seed", 0),
                        Json.str(m, "assetFile", ""), SkinModel.parse(Json.str(m, "model", "")), Json.str(m, "hash", ""),
                        UUID.fromString(Json.str(m, "playerUuid", "")), Json.str(m, "nickname", ""),
                        Json.num(m, "createdAt", System.currentTimeMillis()), Json.str(m, "visualKey", ""));
                if (e.id().isEmpty() || !SkinTextureCache.validHash(e.hash())) throw new IllegalArgumentException("bad id/hash");
                byId.put(e.id(), e);
                byPlayer.put(e.playerUuid(), e);
            } catch (RuntimeException ex) {
                NicksLog.warn("Skipping invalid fictional skin record: " + ex.getMessage());
            }
        }
    }

    /** Coalesced background save, so assigning thousands of skins does not rewrite the file thousands of times. */
    private synchronized void save() {
        if (pendingSave != null && !pendingSave.isDone()) return;
        try {
            pendingSave = saver.schedule(this::flush, cfg.storage.saveDelaySeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // shut down already; close() performed the final flush
        }
    }

    /** Write fictional_skins.json now. Snapshot and write are one step so stale snapshots never overwrite newer ones. */
    public void flush() {
        synchronized (flushLock) {
            List<Object> list = new ArrayList<>();
            synchronized (this) {
                for (Entry e : byId.values()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.id());
                    m.put("kind", e.kind());
                    m.put("seed", e.seed());
                    m.put("assetFile", e.assetFile());
                    m.put("model", e.model().name());
                    m.put("hash", e.hash());
                    m.put("playerUuid", e.playerUuid().toString());
                    m.put("nickname", e.nickname());
                    m.put("createdAt", e.createdAt());
                    m.put("visualKey", e.visualKey());
                    list.add(m);
                }
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schema", SCHEMA);
            root.put("skins", list);
            try {
                file.write(root);
            } catch (IOException ex) {
                NicksLog.error("Could not save fictional_skins.json", ex);
            }
        }
    }

    private static java.util.concurrent.ScheduledThreadPoolExecutor newSaver() {
        var ex = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "Nicks-SkinDB");
            t.setDaemon(true);
            return t;
        });
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return ex;
    }

    @Override
    public void close() {
        saver.shutdown();
        try { saver.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        flush();
    }
}
