package dev.nicks.compat;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.util.function.Predicate;

/**
 * VERIFY ON 1.21.11: the only place that touches the command-permission API (it was reworked around 1.21.9-1.21.11).
 * Admin = gamemaster level (op level 2), the same bar as /gamemode.
 */
public final class PermissionCompat {
    private PermissionCompat() {}

    public static Predicate<ServerCommandSource> admin() {
        // 1.21.11 (permission-set API):
        return CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK);
        // 1.21.1 - 1.21.8 equivalent:
        // return source -> source.hasPermissionLevel(2);
    }
}
