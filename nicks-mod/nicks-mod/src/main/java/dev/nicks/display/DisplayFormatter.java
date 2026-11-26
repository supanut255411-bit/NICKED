package dev.nicks.display;

import dev.nicks.NicksCore;
import dev.nicks.core.PlayerIdentity;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Turns identities into Text components. One cached Text per identity revision keeps getName() cheap. */
public final class DisplayFormatter {
    private record Cached(long revision, String nick, Text text) {}

    private final NicksCore core;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public DisplayFormatter(NicksCore core) { this.core = core; }

    /** The nickname as Text, or null when this player has no active nickname. */
    public Text nicknameFor(UUID uuid) {
        PlayerIdentity id = core.manager.get(uuid).orElse(null);
        if (id == null || !id.active() || id.nickname() == null) return null;
        Cached c = cache.get(uuid);
        if (c != null && c.revision() == id.revision() && c.nick().equals(id.nickname())) return c.text();
        MutableText t = Text.literal(id.nickname());
        Formatting f = Formatting.byName(core.config.nickname.color);
        if (f != null && f.isColor()) t = t.formatted(f);
        cache.put(uuid, new Cached(id.revision(), id.nickname(), t));
        return t;
    }

    /** Tab-list entry: nickname with the player's team prefix/suffix/colour applied, as vanilla would render it. */
    public Text tabName(ServerPlayerEntity player) {
        Text n = nicknameFor(player.getUuid());
        return n == null ? null : Team.decorateName(player.getScoreboardTeam(), n);
    }

    public void forget(UUID uuid) { cache.remove(uuid); }
}
