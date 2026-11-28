package dev.nicks;

import dev.nicks.api.NicksApi;
import dev.nicks.api.NicksEvents;
import dev.nicks.command.Msg;
import dev.nicks.compat.ProfileCompat;
import dev.nicks.core.*;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates every identity change. Server-thread steps (validation, events, applying, packets) are separated from
 * worker-thread steps (Mojang lookups, texture downloads, file IO) so the tick never blocks. Each request carries a
 * generation number: if the same player gets a newer command first, the older result is dropped. Skin and cape always
 * change together, from one source; a failure anywhere keeps the previous identity.
 */
public final class NicksService {
    public interface Reporter {
        void ok(String m);
        void warn(String m);
        void fail(String m);
        default void info(String m) { ok(m); }
        default void complete(boolean success) { }
    }

    private final NicksCore core;

    NicksService(NicksCore core) { this.core = core; }

    private String t(String key, Object... args) { return Msg.t(core.config, key, args); }

    // ---- commands ---------------------------------------------------------

    /** All targets get the same nickname (and therefore the same appearance): /nicks set @a Steve. */
    public void set(Reporter rep, Collection<ServerPlayerEntity> targets, String nickname, boolean keepSkin) {
        for (ServerPlayerEntity p : targets) submit(rep, p, nickname, keepSkin ? SkinMode.KEEP : SkinMode.FROM_NICKNAME, null, false);
    }

    public void random(Reporter rep, Collection<ServerPlayerEntity> targets) {
        if (!core.config.nickname.enableRandom) { rep.fail("Random nicknames are disabled in the config"); return; }
        if (!core.pool.loaded()) { rep.fail("The username pool is not loaded: check the server log and /nicks reload"); return; }
        Set<String> used = new HashSet<>(core.manager.activeNicknameKeys());
        for (ServerPlayerEntity online : core.server.getPlayerManager().getPlayerList()) used.add(Nicknames.key(ProfileCompat.name(online.getGameProfile())));
        for (ServerPlayerEntity p : targets) {
            Optional<String> n = core.pool.pick(x -> used.contains(Nicknames.key(x)));
            if (n.isEmpty()) { rep.fail("Could not find an unused nickname in the pool for " + p.getNameForScoreboard()); continue; }
            used.add(Nicknames.key(n.get())); // two players in one command never get the same random name
            submit(rep, p, n.get(), SkinMode.FROM_NICKNAME, null, false);
        }
    }

    public void skinSet(Reporter rep, Collection<ServerPlayerEntity> targets, String source) {
        for (ServerPlayerEntity p : targets) submit(rep, p, null, source == null ? SkinMode.FROM_NICKNAME : SkinMode.FROM_SOURCE, source, false);
    }

    public void skinRefresh(Reporter rep, Collection<ServerPlayerEntity> targets) {
        for (ServerPlayerEntity p : targets) submit(rep, p, null, SkinMode.REFRESH, null, false);
    }

    public void skinReroll(Reporter rep, Collection<ServerPlayerEntity> targets, boolean force) {
        for (ServerPlayerEntity p : targets) submit(rep, p, null, SkinMode.REROLL, null, force);
    }

    public void reset(Reporter rep, Collection<ServerPlayerEntity> targets) {
        for (ServerPlayerEntity p : targets) resetOne(rep, p);
    }

    public void lock(Reporter rep, Collection<ServerPlayerEntity> targets, boolean locked) {
        if (!core.config.skin.enableSkinLocking) { rep.fail("Skin locking is disabled in the config"); return; }
        for (ServerPlayerEntity p : targets) {
            PlayerIdentity cur = identityOf(p);
            core.manager.apply(p.getUuid(), -1, null, b -> b.skinLocked = locked);
            rep.ok(t(locked ? "lock.on" : "lock.off", cur.effectiveName()));
        }
    }

    public void info(Reporter rep, Collection<ServerPlayerEntity> targets) {
        int online = core.server.getPlayerManager().getPlayerList().size(), modded = core.network.moddedViewers();
        for (ServerPlayerEntity p : targets) {
            PlayerIdentity id = identityOf(p);
            rep.info("Original username: " + id.originalUsername());
            rep.info("Current nickname: " + (id.active() && id.nickname() != null ? id.nickname() : "(none)"));
            rep.info("UUID: " + id.playerUuid() + " (unchanged)");
            rep.info("Identity type: " + id.identityType() + (id.active() ? "" : " (no identity applied)"));
            rep.info("Appearance source: " + (id.sourceName().isEmpty() ? "(none)" : id.sourceName()) + " [" + id.skinSource() + "]");
            rep.info("Skin: " + (id.defaultSkin() ? "default skin of the source account" : id.skinHash().isEmpty() ? "none (original)" : "custom") + ", model " + id.skinModel());
            rep.info("Cape: " + (id.capeHash().isEmpty() ? (id.hasAppearance() ? "none (the source has no cape)" : "original") : "source cape"));
            rep.info("Skin and cape locked: " + (id.skinLocked() ? "yes" : "no"));
            rep.info("Synchronized: revision " + id.revision() + ", " + modded + "/" + online + " connected clients have the mod");
        }
    }

    public void history(Reporter rep, Collection<ServerPlayerEntity> targets) {
        for (ServerPlayerEntity p : targets) {
            PlayerIdentity id = identityOf(p);
            rep.info(id.originalUsername() + " previous nicknames: " + (id.nicknameHistory().isEmpty() ? "(none)" : String.join(", ", id.nicknameHistory())));
        }
    }

    public void reload(Reporter rep) {
        try {
            core.config.reload(core.configDir.resolve("config.json"));
        } catch (Exception e) {
            rep.fail("Reload failed, keeping the previous settings: " + e.getMessage());
            return;
        }
        core.borrowed.reloadAssets();
        NamePool.LoadResult pool = core.loadPool();
        rep.ok(t("reload.success"));
        if (pool.accepted()) rep.ok(t("pool.reloaded", pool.names()));
        else rep.fail(t("pool.problem", pool.message()) + (core.pool.loaded() ? " (still using the previous pool: " + core.pool.size() + " names)" : ""));
    }

    // ---- API entry points (server thread hop + future) -------------------

    public CompletableFuture<NicksApi.Result> apiSetNickname(UUID uuid, String nick) { return api(uuid, nick, SkinMode.FROM_NICKNAME, null); }

    public CompletableFuture<NicksApi.Result> apiSetSkin(UUID uuid, String source) { return api(uuid, null, SkinMode.FROM_SOURCE, source); }

    public CompletableFuture<NicksApi.Result> apiReset(UUID uuid) {
        CompletableFuture<NicksApi.Result> f = new CompletableFuture<>();
        core.server.execute(() -> {
            ServerPlayerEntity p = core.server.getPlayerManager().getPlayer(uuid);
            if (p == null) { f.complete(new NicksApi.Result(false, t("fail.offline"))); return; }
            resetOne(collector(f), p);
        });
        return f;
    }

    private CompletableFuture<NicksApi.Result> api(UUID uuid, String nick, SkinMode mode, String source) {
        CompletableFuture<NicksApi.Result> f = new CompletableFuture<>();
        core.server.execute(() -> {
            ServerPlayerEntity p = core.server.getPlayerManager().getPlayer(uuid);
            if (p == null) { f.complete(new NicksApi.Result(false, t("fail.offline"))); return; }
            submit(collector(f), p, nick, mode, source, false);
        });
        return f;
    }

    private static Reporter collector(CompletableFuture<NicksApi.Result> f) {
        StringBuilder log = new StringBuilder();
        return new Reporter() {
            @Override public void ok(String m) { log.append(m).append('\n'); }
            @Override public void warn(String m) { log.append(m).append('\n'); }
            @Override public void fail(String m) { log.append(m).append('\n'); }
            @Override public void complete(boolean success) { f.complete(new NicksApi.Result(success, log.toString().trim())); }
        };
    }

    // ---- the identity change pipeline --------------------------------------

    private PlayerIdentity identityOf(ServerPlayerEntity p) {
        return core.manager.getOrCreate(p.getUuid(), ProfileCompat.name(p.getGameProfile()));
    }

    private void submit(Reporter rep, ServerPlayerEntity target, String newNick, SkinMode mode, String source, boolean force) {
        UUID uuid = target.getUuid();
        PlayerIdentity cur = identityOf(target);
        if (newNick != null) {
            Optional<String> bad = Nicknames.validate(newNick, core.config);
            if (bad.isPresent()) { rep.fail(bad.get()); rep.complete(false); return; }
            String conflict = core.manager.nicknameConflict(uuid, newNick); // null unless duplicates are switched off
            if (conflict != null) { rep.fail(conflict); rep.complete(false); return; }
        }
        ChangePlanner.Plan plan = ChangePlanner.plan(cur, mode, force, core.config.skin.enableSkinLocking);
        if (!plan.ok()) { rep.fail(t(plan.error())); rep.complete(false); return; }
        if (plan.warning() != null) rep.warn(t(plan.warning()));
        final SkinMode eff = plan.mode();
        String oldNick = cur.active() ? cur.nickname() : null;
        if (newNick != null && !NicksEvents.BEFORE_NICKNAME_CHANGE.invoker().allow(uuid, oldNick, newNick)) {
            rep.fail(t("fail.cancelled")); rep.complete(false); return;
        }
        boolean changesSkin = eff == SkinMode.FROM_NICKNAME || eff == SkinMode.FROM_SOURCE || eff == SkinMode.REROLL;
        if (changesSkin && !NicksEvents.BEFORE_SKIN_CHANGE.invoker().allow(uuid, source != null ? source : (newNick != null ? newNick : cur.skinSource()))) {
            rep.fail(t("fail.cancelled")); rep.complete(false); return;
        }

        final long gen = core.manager.nextGeneration(uuid); // older in-flight requests for this player are now stale
        final String resolveName = newNick != null ? newNick : (cur.active() && cur.nickname() != null ? cur.nickname() : cur.originalUsername());
        final String before = cur.effectiveName();
        CompletableFuture.supplyAsync(() -> compute(uuid, resolveName, eff, source, cur), core.io)
                .whenComplete((outcome, err) -> core.server.execute(() -> finish(rep, uuid, gen, newNick, eff, before, cur, outcome, err)));
    }

    /** Worker thread. Returns null for "keep the current skin and cape". */
    private SkinPipeline.Outcome compute(UUID uuid, String name, SkinMode mode, String source, PlayerIdentity cur) {
        try {
            return switch (mode) {
                case KEEP -> null;
                case FROM_NICKNAME -> core.pipeline.forNickname(uuid, name, false);
                case FROM_SOURCE -> fromSource(source);
                case REROLL -> core.pipeline.borrowed(name, true);
                case REFRESH -> {
                    if (cur.identityType() == IdentityType.REAL_ACCOUNT && cur.skinSource().startsWith("official:")) {
                        yield core.pipeline.forSkinSource(cur.skinSource().substring("official:".length()), true); // skin AND cape again
                    }
                    yield cur.identityType() == IdentityType.FICTIONAL ? core.pipeline.borrowed(name, false) : null;
                }
            };
        } catch (RuntimeException e) {
            return new SkinPipeline.Failed("Internal error: " + e, false);
        }
    }

    private SkinPipeline.Outcome fromSource(String source) {
        int colon = source.indexOf(':');
        if (colon > 0) {
            NicksApi.SkinProvider p = NicksApi.skinProvider(source.substring(0, colon));
            if (p != null) {
                try {
                    Optional<NicksApi.ProvidedSkin> ps = p.resolve(source.substring(colon + 1));
                    if (ps.isEmpty()) return new SkinPipeline.Failed("Skin provider has no skin '" + source + "'", false);
                    SkinModel model = ps.get().slim() ? SkinModel.SLIM : SkinModel.CLASSIC;
                    SkinData d = SkinImages.fromPng(ps.get().png(), model, core.config.skin.maxSkinBytes);
                    core.skinCache.put(d);
                    Appearance a = new Appearance(null, source, "provider", d, model, null, false, "", System.currentTimeMillis());
                    return new SkinPipeline.Resolved(IdentityType.FICTIONAL, "provider:" + source, a, source);
                } catch (SkinException | RuntimeException e) {
                    return new SkinPipeline.Failed("Skin provider failed: " + e.getMessage(), false);
                }
            }
        }
        if (!Nicknames.isValidAccountName(source)) return new SkinPipeline.Failed("'" + source + "' is not a valid Minecraft account name", false);
        return core.pipeline.forSkinSource(source, false);
    }

    /** Server thread: apply the result atomically, then synchronize everyone. */
    private void finish(Reporter rep, UUID uuid, long gen, String newNick, SkinMode mode, String beforeName,
                        PlayerIdentity cur, SkinPipeline.Outcome outcome, Throwable err) {
        if (err != null) {
            NicksLog.error("Identity change failed for " + uuid, err);
            rep.fail("Internal error: " + err.getMessage()); rep.warn(t("fail.previous")); rep.complete(false);
            return;
        }
        if (outcome instanceof SkinPipeline.Failed f) {
            rep.fail(f.reason()); rep.warn(t("fail.previous")); rep.complete(false);
            return;
        }
        SkinPipeline.Resolved r = (SkinPipeline.Resolved) outcome; // null when skin and cape are kept
        PlayerIdentityManager.UpdateResult res = core.manager.apply(uuid, gen, newNick, b -> {
            if (newNick != null) b.nickname = newNick;
            if (newNick != null || r != null) b.active = true;
            if (r != null) b.setAppearance(r.type(), r.skinSource(), r.appearance()); // skin AND cape, from one source
        });
        switch (res.status()) {
            case STALE -> { rep.warn(t("fail.stale")); rep.complete(false); return; }
            case CONFLICT, UNKNOWN_PLAYER -> { rep.fail(res.detail()); rep.complete(false); return; }
            default -> { }
        }
        PlayerIdentity neu = res.identity();
        if (mode == SkinMode.REFRESH && r != null) {
            core.network.invalidate(cur.skinHash(), cur.capeHash());
            core.network.invalidate(neu.skinHash(), neu.capeHash());
        }
        if (newNick != null) rep.ok(t("set.success", beforeName, newNick));
        if (r != null) {
            Appearance a = r.appearance();
            switch (r.type()) {
                case REAL_ACCOUNT -> {
                    rep.ok(t("account.real", r.canonicalName()));
                    rep.ok(t(a.usesDefaultSkin() ? "skin.default" : "skin.official"));
                    rep.ok(t(a.capeAvailable() ? "cape.official" : "cape.none"));
                }
                case FICTIONAL -> {
                    if (r.skinSource().startsWith("provider:")) rep.ok(t("skin.provider"));
                    else if (mode == SkinMode.FROM_NICKNAME) rep.ok(t("account.fictional", r.canonicalName(), a.sourceName()));
                    else rep.ok(t("skin.borrowed", a.sourceName()));
                    if (a.capeAvailable()) rep.ok(t("cape.borrowed"));
                }
                default -> { }
            }
        } else if (mode == SkinMode.KEEP) {
            rep.ok(t("skin.keep"));
        }
        refreshDisplay(rep, neu);
        rep.complete(true);
    }

    private void resetOne(Reporter rep, ServerPlayerEntity target) {
        UUID uuid = target.getUuid();
        PlayerIdentity cur = identityOf(target);
        if (!NicksEvents.BEFORE_IDENTITY_RESET.invoker().allow(uuid)) { rep.fail(t("fail.cancelled")); rep.complete(false); return; }
        long gen = core.manager.nextGeneration(uuid); // cancels anything still in flight for this player
        PlayerIdentityManager.UpdateResult res = core.manager.apply(uuid, gen, null, PlayerIdentity.Builder::clearDisplayed);
        if (res.status() != PlayerIdentityManager.Status.APPLIED) { rep.fail(res.detail()); rep.complete(false); return; }
        rep.ok(t("reset.success", cur.originalUsername()));
        refreshDisplay(rep, res.identity());
        rep.complete(true);
    }

    /** Tab list entry + custom identity packet for every viewer. */
    private void refreshDisplay(Reporter rep, PlayerIdentity id) {
        ServerPlayerEntity online = core.server.getPlayerManager().getPlayer(id.playerUuid());
        if (online == null) { rep.warn(t("disconnected")); return; }
        // getPlayerListName() is mixed in, so this packet carries the new tab-list display name (or clears it)
        core.server.getPlayerManager().sendToAll(new PlayerListS2CPacket(PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME, online));
        int synced = core.network.broadcast(id);
        int total = core.server.getPlayerManager().getPlayerList().size();
        if (id.hasAppearance()) {
            rep.ok(t("sync", synced));
            if (total > synced) rep.warn(t("sync.vanilla", total - synced));
        } else if (total > synced) {
            rep.info(synced + " client(s) updated; " + (total - synced) + " without the mod");
        }
    }
}
