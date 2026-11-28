package dev.nicks;

import dev.nicks.api.NicksEvents;
import dev.nicks.core.*;
import dev.nicks.display.DisplayFormatter;
import dev.nicks.net.IdentityNetwork;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything the server side of the mod needs, created at SERVER_STARTING and closed at SERVER_STOPPED.
 * Global settings and the username pool live in config/nicks/; per-world data (identities, borrowed skins, donor list,
 * skin and cape caches) lives in &lt;world&gt;/nicks/ so different worlds never share identities.
 */
public final class NicksCore implements AutoCloseable {
    /** The default random-nickname database, bundled in the jar so it can always be restored. */
    public static final String BUNDLED_POOL = "/nicks/nicks_1_million_usernames.txt";
    public static final String DEFAULT_POOL_FILE = "nicks_1_million_usernames.txt";

    public final MinecraftServer server;
    public final Path configDir;
    public final Path worldDir;
    public final NicksConfig config;
    public final PlayerIdentityManager manager;
    public final OfficialProfileResolver resolver;
    public final SkinTextureCache skinCache;
    public final SkinTextureCache capeCache;
    public final NamePool pool;
    public final BorrowedSkinRegistry borrowed;
    public final SkinPipeline pipeline;
    public final ExecutorService io;
    public final DisplayFormatter formatter;
    public final IdentityNetwork network;
    public final NicksService service;

    private NicksCore(MinecraftServer server) throws IOException {
        this.server = server;
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve("nicks");
        this.worldDir = server.getSavePath(WorldSavePath.ROOT).resolve("nicks");
        Files.createDirectories(configDir);
        Files.createDirectories(worldDir);
        this.config = NicksConfig.load(configDir.resolve("config.json"));

        HttpTransport http = new HttpTransport.Jdk();
        this.manager = new PlayerIdentityManager(config, new AtomicJsonFile(worldDir.resolve("identities.json")));
        this.skinCache = new SkinTextureCache(worldDir.resolve("skin_cache"), config.skin.maxCacheEntries);
        this.capeCache = new SkinTextureCache(worldDir.resolve("cape_cache"), config.skin.maxCacheEntries);
        this.pool = new NamePool(config, new Random());
        loadPool();
        this.resolver = new OfficialProfileResolver(http, config);
        SkinTextureDownloader downloader = new SkinTextureDownloader(http, config);
        this.borrowed = new BorrowedSkinRegistry(config, http, resolver, downloader, skinCache, capeCache, pool,
                new AtomicJsonFile(worldDir.resolve("skin_donors.json")), new AtomicJsonFile(worldDir.resolve("borrowed_skins.json")),
                safeAssetDir());
        this.pipeline = new SkinPipeline(config, resolver, downloader, skinCache, capeCache, borrowed);
        AtomicInteger n = new AtomicInteger();
        this.io = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Nicks-Worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.formatter = new DisplayFormatter(this);
        this.network = new IdentityNetwork(this);
        this.service = new NicksService(this);

        manager.addListener(this::fireAfterEvents);
        pruneCaches();
        borrowed.startBackground();
    }

    public static NicksCore start(MinecraftServer server) {
        try {
            return new NicksCore(server);
        } catch (IOException e) {
            throw new IllegalStateException("Nicks could not initialise its storage", e);
        }
    }

    /**
     * (Re)load the username pool from config/nicks/nickname-pools/. A missing default file is restored from the jar. If
     * the file is corrupted the previously loaded pool stays in use (and the problem is reported); only when there never
     * was a valid pool is the bundled full database used instead. There is no tiny built-in list.
     *
     * @return the result of loading the external file
     */
    public synchronized NamePool.LoadResult loadPool() {
        Path dir = configDir.resolve("nickname-pools");
        String name = poolFileName();
        Path file = dir.resolve(name);
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file) && name.equals(DEFAULT_POOL_FILE)) {
                restoreBundledPool(file);
                NicksLog.warn("The username pool " + file + " was missing; restored the default copy from the mod jar");
            }
        } catch (IOException e) {
            NicksLog.error("Could not restore the default username pool", e);
        }
        NamePool.LoadResult r;
        try (InputStream in = Files.newInputStream(file)) {
            r = pool.load(in, file.toString());
        } catch (IOException e) {
            r = new NamePool.LoadResult(false, 0, 0, 0, file + ": cannot be read (" + e.getMessage() + ")");
        }
        if (r.accepted()) {
            NicksLog.info(r.message());
            return r;
        }
        NicksLog.error("Username pool problem: " + r.message() + (pool.loaded() ? " (keeping the previously loaded pool)" : ""), null);
        if (!pool.loaded()) { // never silently run without the database: use the full bundled copy, loudly
            InputStream bundled = NicksCore.class.getResourceAsStream(BUNDLED_POOL);
            if (bundled != null) {
                NamePool.LoadResult b = pool.load(bundled, "bundled default pool");
                NicksLog.warn("Using the bundled default username pool instead: " + b.message());
            } else {
                NicksLog.error("The bundled username pool is missing from the jar: /nicks random is unavailable", null);
            }
        }
        return r;
    }

    private String poolFileName() {
        try {
            Path n = Path.of(config.nickname.poolFile).getFileName(); // never a path: stays inside nickname-pools/
            if (n != null && !n.toString().isBlank()) return n.toString();
        } catch (RuntimeException ignored) { }
        return DEFAULT_POOL_FILE;
    }

    private static void restoreBundledPool(Path target) throws IOException {
        try (InputStream in = NicksCore.class.getResourceAsStream(BUNDLED_POOL)) {
            if (in == null) throw new IOException("the bundled username pool is missing from the mod jar");
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Skin assets must live inside config/nicks/ (no arbitrary local file access from a config value). */
    private Path safeAssetDir() {
        Path p = configDir.resolve(config.skin.assetDirectory).normalize();
        if (!p.startsWith(configDir.normalize())) {
            NicksLog.warn("skin.assetDirectory must stay inside config/nicks/; using skin_assets instead");
            p = configDir.resolve("skin_assets");
        }
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) { }
        return p;
    }

    private void pruneCaches() {
        Set<String> referenced = new HashSet<>(borrowed.referencedHashes());
        for (PlayerIdentity id : manager.all()) {
            referenced.add(id.skinHash());
            referenced.add(id.capeHash());
        }
        int removed = skinCache.prune(referenced, 30L * 24 * 3600 * 1000) + capeCache.prune(referenced, 30L * 24 * 3600 * 1000);
        if (removed > 0) NicksLog.info("Pruned " + removed + " unused cached textures");
    }

    private void fireAfterEvents(PlayerIdentity before, PlayerIdentity after) {
        String b = before.active() ? before.nickname() : null, a = after.active() ? after.nickname() : null;
        if (before.active() && !after.active()) NicksEvents.AFTER_IDENTITY_RESET.invoker().reset(after);
        else if (!Objects.equals(b, a)) NicksEvents.AFTER_NICKNAME_CHANGE.invoker().changed(before, after);
        if (!before.skinHash().equals(after.skinHash()) || before.skinModel() != after.skinModel()
                || !before.capeHash().equals(after.capeHash()) || before.defaultSkin() != after.defaultSkin()) {
            NicksEvents.AFTER_SKIN_CHANGE.invoker().changed(before, after);
        }
    }

    @Override
    public void close() {
        io.shutdownNow();
        resolver.close();
        borrowed.close();
        manager.close();
    }
}
