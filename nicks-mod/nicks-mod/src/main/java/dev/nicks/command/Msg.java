package dev.nicks.command;

import dev.nicks.NicksService;
import dev.nicks.core.NicksConfig;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Map;

/** Admin feedback: "[Nicks]" prefix, green = success, yellow = warning, red = failure. Texts are overridable in config. */
public final class Msg {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("set.success", "Successfully changed {0} to {1}"),
            Map.entry("account.real", "Official Minecraft account detected ({0})"),
            Map.entry("account.fictional", "'{0}' is not a Minecraft account: real skin borrowed from {1}"),
            Map.entry("skin.official", "Official skin successfully applied"),
            Map.entry("skin.default", "Account has no custom skin: its default skin applied"),
            Map.entry("skin.borrowed", "Real skin borrowed from {0}"),
            Map.entry("skin.provider", "Skin from provider applied"),
            Map.entry("skin.keep", "Skin and cape left unchanged"),
            Map.entry("cape.official", "Official cape applied"),
            Map.entry("cape.none", "Account has no cape: no cape is shown"),
            Map.entry("cape.borrowed", "Cape of the same account applied"),
            Map.entry("locked.nicknameonly", "Skin and cape are locked: only the nickname changes"),
            Map.entry("fail.norefresh", "This player has no custom skin or cape to refresh"),
            Map.entry("pool.problem", "Username pool NOT reloaded: {0}"),
            Map.entry("pool.reloaded", "Username pool reloaded: {0} names"),
            Map.entry("sync", "Skin synchronized with {0} connected clients"),
            Map.entry("sync.vanilla", "{0} player(s) without the mod only see the name change, not the skin"),
            Map.entry("fail.previous", "Previous identity preserved"),
            Map.entry("fail.stale", "A newer change for this player replaced this one"),
            Map.entry("fail.locked", "The skin is locked (use /nicks skin unlock first)"),
            Map.entry("fail.cancelled", "Another mod cancelled this change"),
            Map.entry("fail.offline", "Player is not online"),
            Map.entry("fail.fictional.only", "Only identities with a borrowed skin can be rerolled (add 'force' to override)"),
            Map.entry("reset.success", "Restored {0}'s original name, skin and cape"),
            Map.entry("lock.on", "Skin locked for {0}"),
            Map.entry("lock.off", "Skin unlocked for {0}"),
            Map.entry("reload.success", "Configuration and skin assets reloaded"),
            Map.entry("disconnected", "Player disconnected; the identity change was saved"));

    private Msg() {}

    public static String t(NicksConfig cfg, String key, Object... args) {
        String s = cfg.messages.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    private static MutableText prefix() { return Text.literal("[Nicks] ").formatted(Formatting.AQUA); }

    public static NicksService.Reporter reporter(ServerCommandSource src) {
        return new NicksService.Reporter() {
            @Override public void ok(String m) { src.sendFeedback(() -> prefix().append(Text.literal(m).formatted(Formatting.GREEN)), false); }
            @Override public void warn(String m) { src.sendFeedback(() -> prefix().append(Text.literal(m).formatted(Formatting.YELLOW)), false); }
            @Override public void fail(String m) { src.sendError(prefix().append(Text.literal(m))); }
            @Override public void info(String m) { src.sendFeedback(() -> prefix().append(Text.literal(m).formatted(Formatting.GRAY)), false); }
        };
    }
}
