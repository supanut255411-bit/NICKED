package dev.nicks;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * Static bridge used by mixins (which cannot hold references). Server side: {@link NicksCore}. Client side: a
 * {@link ClientView} fed by the identity sync packets. In single player both exist; they are told apart by the
 * entity type (ServerPlayerEntity = server world, everything else = client world).
 */
public final class NicksRuntime {
    public interface ClientView {
        /** Nickname to render above this player's head, or null. */
        Text nicknameOf(UUID player);
    }

    private static volatile NicksCore core;
    private static volatile ClientView clientView;

    private NicksRuntime() {}

    public static NicksCore core() { return core; }

    static void setCore(NicksCore c) { core = c; }

    public static void setClientView(ClientView v) { clientView = v; }

    /** Used by the PlayerEntity#getName mixin: the nickname as a Text, or null to keep vanilla behaviour. */
    public static Text nameFor(PlayerEntity player) {
        if (player instanceof ServerPlayerEntity) {
            NicksCore c = core;
            if (c == null || !c.config.display.replaceServerMessages) return null;
            return c.formatter.nicknameFor(player.getUuid());
        }
        ClientView v = clientView;
        return v == null ? null : v.nicknameOf(player.getUuid());
    }

    /** Used by the ServerPlayerEntity#getPlayerListName mixin. */
    public static Text listNameFor(ServerPlayerEntity player) {
        NicksCore c = core;
        if (c == null || !c.config.display.replaceTabList) return null;
        return c.formatter.tabName(player);
    }

    /**
     * Used by the PlayerManager#getPlayer(String) mixin so vanilla commands accept nicknames: {@code /tp ShadowWolf},
     * {@code /gamemode creative ShadowWolf}, {@code /give ShadowWolf ...}. Ambiguous nicknames never resolve.
     */
    public static ServerPlayerEntity adjustLookup(String name, ServerPlayerEntity real) {
        NicksCore c = core;
        if (c == null || !c.config.commands.aliasResolution || name == null) return real;
        if (real != null && !c.config.nickname.allowOriginalUsernameAlias
                && c.manager.activeNickname(real.getUuid()).isPresent()) {
            real = null; // admin chose to hide the real name of nicknamed players
        }
        var match = c.manager.resolveNickname(name);
        if (match.isEmpty() || match.get().ambiguous()) return real;
        ServerPlayerEntity byNick = c.server.getPlayerManager().getPlayer(match.get().uuid());
        if (byNick == null) return real;
        return real == null || c.config.nickname.nicknameFirstResolution ? byNick : real;
    }
}
