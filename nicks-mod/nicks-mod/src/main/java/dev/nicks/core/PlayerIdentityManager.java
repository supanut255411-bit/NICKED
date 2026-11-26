package dev.nicks.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The central identity service. Every feature (tab list, chat, commands, network, API) reads the same
 * {@link PlayerIdentity} objects from here and every change goes through {@link #apply}, which is atomic,
 * revision-stamped, stale-request-safe and persisted.
 */
public final class PlayerIdentityManager implements AutoCloseable {
    public enum Status { APPLIED, STALE, CONFLICT, UNKNOWN_PLAYER }

    public record UpdateResult(Status status, PlayerIdentity identity, String detail) {}

    public record NicknameMatch(UUID uuid, boolean ambiguous) {}

    public interface Listener { void onChange(PlayerIdentity before, PlayerIdentity after); }

    private final NicksConfig cfg;
    private final Map<UUID, PlayerIdentity> identities = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> nicknameIndex = new ConcurrentHashMap<>();
    private final Map<String, UUID> originalIndex = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final IdentityPersistence persistence;

    public PlayerIdentityManager(NicksConfig cfg, AtomicJsonFile identityFile) {
        this.cfg = cfg;
        this.persistence = new IdentityPersistence(identityFile, this::all, cfg);
        for (PlayerIdentity p : persistence.load().values()) {
            identities.put(p.playerUuid(), p);
            index(null, p);
        }
        NicksLog.info("Loaded " + identities.size() + " player identities");
    }

    // ---- reads -----------------------------------------------------------

    public Optional<PlayerIdentity> get(UUID uuid) { return Optional.ofNullable(identities.get(uuid)); }

    public Collection<PlayerIdentity> all() { return new ArrayList<>(identities.values()); }

    /** Active nickname for display, if any. */
    public Optional<String> activeNickname(UUID uuid) {
        PlayerIdentity p = identities.get(uuid);
        return p != null && p.active() && p.nickname() != null ? Optional.of(p.nickname()) : Optional.empty();
    }

    public Set<String> activeNicknameKeys() { return Set.copyOf(nicknameIndex.keySet()); }

    /** Nickname -> player UUID (never guesses between two holders of the same nickname). */
    public Optional<NicknameMatch> resolveNickname(String name) {
        Set<UUID> s = nicknameIndex.get(Nicknames.key(name));
        if (s == null || s.isEmpty()) return Optional.empty();
        UUID first = s.iterator().next();
        return Optional.of(new NicknameMatch(first, s.size() > 1));
    }

    // ---- request generations (stale async results) ------------------------

    /** Start a new change request for this player; older in-flight requests become stale. */
    public long nextGeneration(UUID uuid) {
        return generations.computeIfAbsent(uuid, k -> new AtomicLong()).incrementAndGet();
    }

    public boolean isCurrent(UUID uuid, long generation) {
        AtomicLong g = generations.get(uuid);
        return g != null && g.get() == generation;
    }

    // ---- writes ----------------------------------------------------------

    /** Ensure a record exists for a joining/known player and keep the original username current. */
    public synchronized PlayerIdentity getOrCreate(UUID uuid, String username) {
        PlayerIdentity old = identities.get(uuid);
        long now = System.currentTimeMillis();
        if (old == null) {
            PlayerIdentity fresh = PlayerIdentity.initial(uuid, username, now);
            identities.put(uuid, fresh);
            index(null, fresh);
            persistence.markDirty();
            return fresh;
        }
        if (!old.originalUsername().equals(username)) {
            PlayerIdentity updated = old.toBuilder().build();
            PlayerIdentity.Builder b = updated.toBuilder();
            b.originalUsername = username;
            PlayerIdentity neu = b.build();
            identities.put(uuid, neu);
            index(old, neu);
            persistence.markDirty();
            return neu;
        }
        return old;
    }

    /** Why this nickname cannot be used by {@code uuid}, or null when it is free. */
    public synchronized String nicknameConflict(UUID uuid, String nickname) {
        String key = Nicknames.key(nickname);
        UUID owner = originalIndex.get(key);
        if (owner != null && !owner.equals(uuid)) return "Nickname matches another player's account name";
        if (!cfg.nickname.allowDuplicateNicknames) {
            Set<UUID> s = nicknameIndex.get(key);
            if (s != null) for (UUID u : s) if (!u.equals(uuid)) return "Nickname is already assigned to another player";
        }
        return null;
    }

    /**
     * The single authoritative update path.
     *
     * @param generation      request generation from {@link #nextGeneration}, or -1 to skip the stale check
     * @param claimNickname   nickname the mutator will set (validated for conflicts atomically), or null
     * @param mutator         edits a copy of the identity; revision/timestamps/history are handled here
     */
    public synchronized UpdateResult apply(UUID uuid, long generation, String claimNickname,
                                           Consumer<PlayerIdentity.Builder> mutator) {
        PlayerIdentity old = identities.get(uuid);
        if (old == null) return new UpdateResult(Status.UNKNOWN_PLAYER, null, "Unknown player");
        if (generation >= 0 && !isCurrent(uuid, generation)) {
            return new UpdateResult(Status.STALE, old, "Superseded by a newer change");
        }
        if (claimNickname != null) {
            String conflict = nicknameConflict(uuid, claimNickname);
            if (conflict != null) return new UpdateResult(Status.CONFLICT, old, conflict);
        }
        PlayerIdentity.Builder b = old.toBuilder();
        mutator.accept(b);
        String oldNick = old.active() ? old.nickname() : null;
        String newNick = b.active ? b.nickname : null;
        if (cfg.nickname.preserveHistory && oldNick != null && !oldNick.equalsIgnoreCase(newNick == null ? "" : newNick)) {
            b.nicknameHistory.add(oldNick);
            while (b.nicknameHistory.size() > cfg.nickname.historyLimit) b.nicknameHistory.remove(0);
        }
        b.revision = old.revision() + 1;
        b.lastUpdated = System.currentTimeMillis();
        PlayerIdentity neu = b.build();
        identities.put(uuid, neu);
        index(old, neu);
        persistence.markDirty();
        for (Listener l : listeners) {
            try {
                l.onChange(old, neu);
            } catch (RuntimeException e) {
                NicksLog.warn("Identity listener failed", e);
            }
        }
        NicksLog.debug("Identity saved uuid=" + uuid + " rev=" + neu.revision());
        return new UpdateResult(Status.APPLIED, neu, "");
    }

    public void addListener(Listener l) { listeners.add(l); }

    public void flush() throws java.io.IOException { persistence.flush(); }

    @Override
    public void close() { persistence.close(); }

    // ---- indexes ---------------------------------------------------------

    private void index(PlayerIdentity old, PlayerIdentity neu) {
        if (old != null) {
            if (old.active() && old.nickname() != null) {
                nicknameIndex.computeIfPresent(Nicknames.key(old.nickname()), (k, s) -> {
                    s.remove(old.playerUuid());
                    return s.isEmpty() ? null : s;
                });
            }
            if (!old.originalUsername().isEmpty()) originalIndex.remove(Nicknames.key(old.originalUsername()), old.playerUuid());
        }
        if (neu.active() && neu.nickname() != null) {
            nicknameIndex.computeIfAbsent(Nicknames.key(neu.nickname()), k -> ConcurrentHashMap.newKeySet()).add(neu.playerUuid());
        }
        if (!neu.originalUsername().isEmpty()) originalIndex.put(Nicknames.key(neu.originalUsername()), neu.playerUuid());
    }
}
