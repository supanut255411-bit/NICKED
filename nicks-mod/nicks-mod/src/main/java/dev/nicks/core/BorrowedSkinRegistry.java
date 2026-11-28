package dev.nicks.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Skins for nicknames that are NOT real Minecraft accounts. Nothing is ever generated: the skin is always borrowed from
 * a real existing source ("donor"):
 * <ul>
 *   <li>mojang-accounts: real accounts discovered through the official Mojang API (10 names per request, taken from the
 *       username pool); their skin and cape come from the same profile;</li>
 *   <li>skindb: a public skin database that returns Mojang-hosted texture URLs;</li>
 *   <li>assets: PNG files the admin dropped into the skin asset folder.</li>
 * </ul>
 * A nickname keeps the same borrowed skin forever (and every player using that nickname shares it). Different nicknames
 * get different donors unless duplicates are allowed. If no real source can be reached the request fails and the
 * previous identity is kept.
 */
public final class BorrowedSkinRegistry implements AutoCloseable {
    public static final int SCHEMA = 1;

    /** A real skin (and optional cape) that can be borrowed. {@code id} is account:&lt;uuid&gt;, skindb:&lt;id&gt; or asset:&lt;file&gt;. */
    public record Donor(String id, String name, UUID uuid, String skinUrl, String capeUrl, String assetFile,
                        SkinModel model, long fetchedAt) {
        public String kind() { return id.substring(0, id.indexOf(':')); }
    }

    public record Assignment(String key, String nickname, Donor donor, String skinHash, String capeHash, long assignedAt) {}

    private final NicksConfig cfg;
    private final HttpTransport http;
    private final OfficialProfileResolver resolver;
    private final SkinTextureDownloader downloader;
    private final SkinTextureCache skinCache, capeCache;
    private final NamePool pool;
    private final AtomicJsonFile donorFile, assignFile;
    private final Path assetDir;
    private final Random random = new Random();

    private final Map<String, Donor> donors = new LinkedHashMap<>();            // guarded by this
    private final Map<String, Assignment> assignments = new LinkedHashMap<>();  // guarded by this
    private final Set<String> reserved = new HashSet<>();                       // donor ids in use, guarded by this
    private final Map<String, SkinData> assetSkins = new LinkedHashMap<>();     // guarded by this
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final Object harvestLock = new Object();
    private final Object flushLock = new Object();
    private volatile long skinDbRetryAt;

    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(daemon("Nicks-SkinDB"));
    private ScheduledExecutorService harvester;
    private ScheduledFuture<?> pendingSave;

    public BorrowedSkinRegistry(NicksConfig cfg, HttpTransport http, OfficialProfileResolver resolver,
                                SkinTextureDownloader downloader, SkinTextureCache skinCache, SkinTextureCache capeCache,
                                NamePool pool, AtomicJsonFile donorFile, AtomicJsonFile assignFile, Path assetDir) {
        this.cfg = cfg;
        this.http = http;
        this.resolver = resolver;
        this.downloader = downloader;
        this.skinCache = skinCache;
        this.capeCache = capeCache;
        this.pool = pool;
        this.donorFile = donorFile;
        this.assignFile = assignFile;
        this.assetDir = assetDir;
        load();
        reloadAssets();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // ---- public API --------------------------------------------------------

    /**
     * The real appearance this nickname uses: the stored one (stable across restarts and shared by everyone with this
     * nickname) or a newly borrowed one. Blocking (network): call from a worker thread.
     */
    public Appearance appearanceFor(String nickname, boolean forceNew) throws SkinException {
        String key = Nicknames.key(nickname);
        synchronized (keyLocks.computeIfAbsent(key, k -> new Object())) {
            Assignment existing;
            synchronized (this) { existing = assignments.get(key); }
            if (existing != null && !forceNew) return fromAssignment(existing);

            String avoid = existing == null ? null : existing.donor().id();
            SkinException last = null;
            for (int attempt = 0; attempt < 6; attempt++) {
                Donor d = pickDonor(avoid);
                if (d == null) break;
                try {
                    Appearance a = build(d);
                    Assignment as = new Assignment(key, nickname, d, a.skin().hash(), a.cape() == null ? "" : a.cape().hash(),
                            System.currentTimeMillis());
                    synchronized (this) {
                        assignments.put(key, as);
                        if (existing != null) unreserveIfUnused(existing.donor().id());
                    }
                    save();
                    return a;
                } catch (SkinException e) {
                    last = e;
                    NicksLog.debug("Donor " + d.id() + " unusable: " + e.getMessage());
                    synchronized (this) { unreserveIfUnused(d.id()); }
                }
            }
            throw last != null ? last : new SkinException("No real skin source is available right now "
                    + "(Mojang/SkinDB unreachable, or every known real skin is already in use; see skin.sources and "
                    + "skin.allowDuplicateBorrowedSkins)");
        }
    }

    public synchronized Optional<Assignment> assignment(String nickname) { return Optional.ofNullable(assignments.get(Nicknames.key(nickname))); }

    public synchronized int donorCount() { return donors.size(); }

    public synchronized int accountDonorCount() {
        int n = 0;
        for (Donor d : donors.values()) if (d.kind().equals("account")) n++;
        return n;
    }

    public synchronized Set<String> referencedHashes() {
        Set<String> s = new HashSet<>();
        for (Assignment a : assignments.values()) { s.add(a.skinHash()); s.add(a.capeHash()); }
        return s;
    }

    /**
     * Discover real accounts: ask Mojang which of 10 random pool names exist, then fetch each hit's profile. Stops after
     * {@code wantNew} donors, {@code maxRequests} requests, the time limit or repeated service failures.
     */
    public int harvest(int maxRequests, int wantNew, long maxMillis) {
        synchronized (harvestLock) {
            if (!pool.loaded() || !cfg.skin.enableOfficialResolution) return 0;
            long deadline = System.nanoTime() + maxMillis * 1_000_000L;
            int added = 0, failures = 0;
            for (int i = 0; i < maxRequests && added < wantNew && failures < 2 && System.nanoTime() < deadline; i++) {
                OfficialProfileResolver.BulkResult br = resolver.bulkExisting(pool.sample(10));
                if (!br.ok()) { failures++; NicksLog.debug("Donor probe failed: " + br.error()); continue; }
                for (OfficialProfileResolver.Found f : br.found()) {
                    String id = "account:" + f.id();
                    synchronized (this) { if (donors.containsKey(id)) continue; }
                    ProfileLookupResult p = resolver.profile(f.id(), f.name());
                    if (p instanceof ProfileLookupResult.Real r) {
                        if (r.skinUrl() == null) continue; // default-skin accounts are not interesting as donors
                        addDonor(new Donor(id, r.canonicalName(), r.profileUuid(), r.skinUrl(), r.capeUrl(), "", r.model(), System.currentTimeMillis()));
                        added++;
                    } else {
                        failures++;
                    }
                }
            }
            if (added > 0) NicksLog.debug("Donor harvest added " + added + " real accounts");
            return added;
        }
    }

    /** Keep a stock of real donor accounts so commands rarely wait for Mojang. */
    public void startBackground() {
        if (harvester != null || !cfg.skin.backgroundDonorHarvest) return;
        harvester = Executors.newSingleThreadScheduledExecutor(daemon("Nicks-Donors"));
        harvester.scheduleWithFixedDelay(() -> {
            try {
                if (cfg.skin.sources.contains("mojang-accounts") && accountDonorCount() < cfg.skin.donorTarget) harvest(8, 10, 15_000);
            } catch (RuntimeException e) {
                NicksLog.warn("Background donor harvest failed", e);
            }
        }, 20, cfg.skin.donorHarvestIntervalSeconds, TimeUnit.SECONDS);
    }

    public synchronized void reloadAssets() {
        donors.values().removeIf(d -> d.kind().equals("asset"));
        assetSkins.clear();
        if (assetDir == null || !Files.isDirectory(assetDir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(assetDir, "*.png")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                try {
                    var raw = SkinImages.decode(Files.readAllBytes(p), cfg.skin.maxSkinBytes);
                    SkinModel model = name.toLowerCase(Locale.ROOT).endsWith(".slim.png") || SkinImages.looksSlim(raw) ? SkinModel.SLIM : SkinModel.CLASSIC;
                    SkinData d = SkinImages.toSkinData(SkinImages.normalize(raw), model);
                    String id = "asset:" + name;
                    assetSkins.put(id, d);
                    donors.put(id, new Donor(id, name, null, null, null, name, model, System.currentTimeMillis()));
                } catch (SkinException | IOException | RuntimeException e) {
                    NicksLog.warn("Skipping skin asset " + name + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            NicksLog.warn("Could not scan skin asset folder: " + e.getMessage());
        }
    }

    // ---- choosing and building ----------------------------------------------

    private Donor pickDonor(String exclude) {
        for (String source : cfg.skin.sources) {
            Donor d = switch (source.toLowerCase(Locale.ROOT)) {
                case "mojang-accounts" -> {
                    Donor x = take(dn -> dn.kind().equals("account"), exclude);
                    if (x == null && harvest(cfg.skin.maxDonorProbeRequests, 3, cfg.skin.donorProbeSeconds * 1000L) > 0) {
                        x = take(dn -> dn.kind().equals("account"), exclude);
                    }
                    yield x;
                }
                case "skindb" -> {
                    Donor x = take(dn -> dn.kind().equals("skindb"), exclude);
                    if (x == null && fetchSkinDb() > 0) x = take(dn -> dn.kind().equals("skindb"), exclude);
                    yield x;
                }
                case "assets" -> take(dn -> dn.kind().equals("asset"), exclude);
                default -> null;
            };
            if (d != null) return d;
        }
        return null;
    }

    /** Pick a random donor nobody uses yet (any donor when duplicates are allowed) and reserve it. */
    private synchronized Donor take(Predicate<Donor> kind, String exclude) {
        List<Donor> c = new ArrayList<>();
        for (Donor d : donors.values()) {
            if (!kind.test(d) || d.id().equals(exclude)) continue;
            if (cfg.skin.allowDuplicateBorrowedSkins || !reserved.contains(d.id())) c.add(d);
        }
        if (c.isEmpty()) return null;
        Donor d = c.get(random.nextInt(c.size()));
        reserved.add(d.id());
        return d;
    }

    private void unreserveIfUnused(String id) {
        for (Assignment a : assignments.values()) if (a.donor().id().equals(id)) return;
        reserved.remove(id);
    }

    private Appearance fromAssignment(Assignment a) throws SkinException {
        Donor d = a.donor();
        Optional<SkinData> skin = skinCache.get(a.skinHash(), d.model());
        SkinData cape = null;
        boolean capeMissing = false;
        if (!a.capeHash().isEmpty()) {
            Optional<SkinData> c = capeCache.get(a.capeHash(), SkinModel.CLASSIC);
            if (c.isPresent()) cape = c.get(); else capeMissing = true;
        }
        if (skin.isPresent() && !capeMissing) return appearance(d, skin.get(), cape, a.assignedAt());
        Appearance rebuilt = build(d); // cache files were deleted: fetch the same donor again
        synchronized (this) {
            assignments.put(a.key(), new Assignment(a.key(), a.nickname(), d, rebuilt.skin().hash(),
                    rebuilt.cape() == null ? "" : rebuilt.cape().hash(), a.assignedAt()));
        }
        save();
        return rebuilt;
    }

    private Appearance build(Donor d) throws SkinException {
        SkinData skin;
        if (!d.assetFile().isEmpty()) {
            synchronized (this) { skin = assetSkins.get(d.id()); }
            if (skin == null) throw new SkinException("Skin asset " + d.assetFile() + " is no longer available");
        } else {
            skin = downloader.download(d.skinUrl(), d.model());
        }
        skinCache.put(skin);
        SkinData cape = null;
        if (cfg.skin.donorCapes && d.capeUrl() != null) { // same account as the skin, never mixed
            cape = downloader.downloadCape(d.capeUrl());
            capeCache.put(cape);
        }
        return appearance(d, skin, cape, System.currentTimeMillis());
    }

    private static Appearance appearance(Donor d, SkinData skin, SkinData cape, long at) {
        return new Appearance(d.uuid(), d.name(), "borrowed:" + d.kind(), skin, d.model(), cape, cape != null, "", at);
    }

    // ---- skin database source ------------------------------------------------

    private int fetchSkinDb() {
        if (System.currentTimeMillis() < skinDbRetryAt || cfg.skin.skinDbUrl.isBlank()) return 0;
        try {
            HttpTransport.Response r = http.get(URI.create(cfg.skin.skinDbUrl), Duration.ofSeconds(cfg.skin.downloadTimeoutSeconds), 262144);
            if (r.status() != 200) throw new IOException("HTTP " + r.status());
            Object parsed = Json.parse(new String(r.body(), StandardCharsets.UTF_8));
            if (!(parsed instanceof List<?> list)) throw new IOException("unexpected response shape");
            int added = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                String mojang = Json.str(Json.obj(m, "urls"), "mojang", null); // only the Mojang-hosted copy is used
                if (mojang == null || !(m.get("id") instanceof Number id)) continue;
                boolean steveArms = Json.bool(Json.obj(m, "Skin"), "steveArms", true);
                String did = "skindb:" + id.longValue();
                synchronized (this) { if (donors.containsKey(did)) continue; }
                addDonor(new Donor(did, "skindb #" + id.longValue(), null, mojang, null, "", steveArms ? SkinModel.CLASSIC : SkinModel.SLIM, System.currentTimeMillis()));
                added++;
            }
            return added;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            skinDbRetryAt = System.currentTimeMillis() + 5 * 60_000L; // do not hammer a service that is down
            NicksLog.debug("SkinDB source unavailable: " + e.getMessage());
            return 0;
        }
    }

    private void addDonor(Donor d) {
        synchronized (this) {
            donors.put(d.id(), d);
            if (donors.size() > cfg.skin.maxDonors) {
                var it = donors.values().iterator();
                while (it.hasNext() && donors.size() > cfg.skin.maxDonors) {
                    Donor old = it.next();
                    if (!old.kind().equals("asset") && !reserved.contains(old.id())) it.remove();
                }
            }
        }
        save();
    }

    // ---- persistence ----------------------------------------------------------

    private void load() {
        Map<String, Object> dRoot = donorFile.read();
        if (dRoot != null && Json.num(dRoot, "schema", 1) <= SCHEMA) {
            for (Object o : Json.list(dRoot, "donors")) {
                Donor d = parseDonor(o);
                if (d != null) donors.put(d.id(), d);
            }
        }
        Map<String, Object> aRoot = assignFile.read();
        if (aRoot != null && Json.num(aRoot, "schema", 1) <= SCHEMA) {
            for (Object o : Json.list(aRoot, "assignments")) {
                if (!(o instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                Donor d = parseDonor(m.get("donor"));
                String skinHash = Json.str(m, "skinHash", ""), capeHash = Json.str(m, "capeHash", "");
                if (d == null || !SkinTextureCache.validHash(skinHash) || (!capeHash.isEmpty() && !SkinTextureCache.validHash(capeHash))) {
                    NicksLog.warn("Skipping invalid borrowed-skin record");
                    continue;
                }
                String key = Json.str(m, "key", "");
                if (key.isEmpty()) continue;
                assignments.put(key, new Assignment(key, Json.str(m, "nickname", key), d, skinHash, capeHash, Json.num(m, "assignedAt", 0)));
                reserved.add(d.id());
            }
        }
    }

    private static Donor parseDonor(Object o) {
        if (!(o instanceof Map<?, ?>)) return null;
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
        try {
            String id = Json.str(m, "id", "");
            if (!id.matches("(account|skindb|asset):.+")) return null;
            String uuid = Json.str(m, "uuid", ""), skinUrl = Json.str(m, "skinUrl", ""), capeUrl = Json.str(m, "capeUrl", "");
            String asset = Json.str(m, "assetFile", "");
            if (asset.isEmpty() && skinUrl.isEmpty()) return null;
            return new Donor(id, Json.str(m, "name", id), uuid.isEmpty() ? null : UUID.fromString(uuid), skinUrl.isEmpty() ? null : skinUrl,
                    capeUrl.isEmpty() ? null : capeUrl, asset, SkinModel.parse(Json.str(m, "model", "")), Json.num(m, "fetchedAt", 0));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> donorMap(Donor d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("name", d.name());
        m.put("uuid", d.uuid() == null ? "" : d.uuid().toString());
        m.put("skinUrl", d.skinUrl() == null ? "" : d.skinUrl());
        m.put("capeUrl", d.capeUrl() == null ? "" : d.capeUrl());
        m.put("assetFile", d.assetFile());
        m.put("model", d.model().name());
        m.put("fetchedAt", d.fetchedAt());
        return m;
    }

    private synchronized void save() {
        if (pendingSave != null && !pendingSave.isDone()) return;
        try {
            pendingSave = saver.schedule(this::flush, cfg.storage.saveDelaySeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // closed already; close() performed the final flush
        }
    }

    /** Write skin_donors.json and borrowed_skins.json now (snapshot + write are one step). */
    public void flush() {
        synchronized (flushLock) {
            List<Object> dl = new ArrayList<>(), al = new ArrayList<>();
            synchronized (this) {
                for (Donor d : donors.values()) if (!d.kind().equals("asset")) dl.add(donorMap(d));
                for (Assignment a : assignments.values()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", a.key());
                    m.put("nickname", a.nickname());
                    m.put("donor", donorMap(a.donor()));
                    m.put("skinHash", a.skinHash());
                    m.put("capeHash", a.capeHash());
                    m.put("assignedAt", a.assignedAt());
                    al.add(m);
                }
            }
            try {
                Map<String, Object> dr = new LinkedHashMap<>();
                dr.put("schema", SCHEMA);
                dr.put("donors", dl);
                donorFile.write(dr);
                Map<String, Object> ar = new LinkedHashMap<>();
                ar.put("schema", SCHEMA);
                ar.put("assignments", al);
                assignFile.write(ar);
            } catch (IOException e) {
                NicksLog.error("Could not save borrowed skin data", e);
            }
        }
    }

    public synchronized Collection<Assignment> assignments() { return new ArrayList<>(assignments.values()); }

    @Override
    public void close() {
        if (harvester != null) harvester.shutdownNow();
        saver.shutdown();
        try { saver.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        flush();
    }
}
