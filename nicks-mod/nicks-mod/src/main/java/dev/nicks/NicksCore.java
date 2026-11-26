package dev.nicks;

import dev.nicks.api.NicksEvents;
import dev.nicks.core.*;
import dev.nicks.display.DisplayFormatter;
import dev.nicks.net.IdentityNetwork;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything the server side of the mod needs, created at SERVER_STARTING and closed at SERVER_STOPPED.
 * Global settings live in config/nicks/; per-world data (identities, fictional skins, skin cache) lives in
 * &lt;world&gt;/nicks/ so different worlds never share identities.
 */
public final class NicksCore implements AutoCloseable {
    public final MinecraftServer server;
    public final Path configDir;
    public final Path worldDir;
    public final NicksConfig config;
    public final PlayerIdentityManager manager;
    public final OfficialProfileResolver resolver;
    public final SkinTextureCache cache;
    public final FictionalSkinRegistry fictional;
    public final SkinPipeline pipeline;
    public final NicknameGenerator names;
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
        this.cache = new SkinTextureCache(worldDir.resolve("skin_cache"), config.skin.maxCacheEntries);
        this.fictional = new FictionalSkinRegistry(config, new AtomicJsonFile(worldDir.resolve("fictional_skins.json")),
                cache, safeAssetDir());
        this.resolver = new OfficialProfileResolver(http, config);
        this.pipeline = new SkinPipeline(config, resolver, new SkinTextureDownloader(http, config), cache, fictional);
        this.names = new NicknameGenerator(config, new Random());
        this.names.loadPools(configDir.resolve("nickname_pools"));
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
        pruneSkinCache();
    }

    public static NicksCore start(MinecraftServer server) {
        try {
            return new NicksCore(server);
        } catch (IOException e) {
            throw new IllegalStateException("Nicks could not initialise its storage", e);
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

    private void pruneSkinCache() {
        Set<String> referenced = new HashSet<>();
        for (PlayerIdentity id : manager.all()) referenced.add(id.skinHash());
        for (var e : fictional.entries()) referenced.add(e.hash());
        int removed = cache.prune(referenced, 30L * 24 * 3600 * 1000);
        if (removed > 0) NicksLog.info("Pruned " + removed + " unused cached skins");
    }

    private void fireAfterEvents(PlayerIdentity before, PlayerIdentity after) {
        String b = before.active() ? before.nickname() : null, a = after.active() ? after.nickname() : null;
        if (before.active() && !after.active()) NicksEvents.AFTER_IDENTITY_RESET.invoker().reset(after);
        else if (!Objects.equals(b, a)) NicksEvents.AFTER_NICKNAME_CHANGE.invoker().changed(before, after);
        if (!before.skinHash().equals(after.skinHash()) || before.skinModel() != after.skinModel()) {
            NicksEvents.AFTER_SKIN_CHANGE.invoker().changed(before, after);
        }
    }

    @Override
    public void close() {
        io.shutdownNow();
        resolver.close();
        fictional.close();
        manager.close();
    }
}
