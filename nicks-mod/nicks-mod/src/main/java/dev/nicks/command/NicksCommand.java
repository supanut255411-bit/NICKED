package dev.nicks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.nicks.NicksCore;
import dev.nicks.NicksRuntime;
import dev.nicks.NicksService;
import dev.nicks.compat.PermissionCompat;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Collection;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /nicks ... (admin only). Targets use the vanilla player selector argument, so @a @p @s @r, player names and
 * nicknames (through the PlayerManager mixin) all work.
 */
public final class NicksCommand {
    private interface Action { void run(NicksService s, NicksService.Reporter r, Collection<ServerPlayerEntity> t); }

    private NicksCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("nicks").requires(PermissionCompat.admin())
                .then(literal("random").then(targets().executes(c -> run(c, (s, r, t) -> s.random(r, t)))))
                .then(literal("set").then(targets().then(nickname().executes(c ->
                        run(c, (s, r, t) -> s.set(r, t, StringArgumentType.getString(c, "nickname"), false))))))
                .then(literal("nicknoskin").then(literal("set").then(targets().then(nickname().executes(c ->
                        run(c, (s, r, t) -> s.set(r, t, StringArgumentType.getString(c, "nickname"), true)))))))
                .then(literal("reset").then(targets().executes(c -> run(c, (s, r, t) -> s.reset(r, t)))))
                .then(literal("info").then(targets().executes(c -> run(c, (s, r, t) -> s.info(r, t)))))
                .then(literal("history").then(targets().executes(c -> run(c, (s, r, t) -> s.history(r, t)))))
                .then(literal("reload").executes(c -> {
                    service().reload(Msg.reporter(c.getSource()));
                    return 1;
                }))
                .then(literal("skin")
                        .then(literal("set").then(targets().executes(c -> run(c, (s, r, t) -> s.skinSet(r, t, null)))
                                .then(argument("source", StringArgumentType.word()).executes(c ->
                                        run(c, (s, r, t) -> s.skinSet(r, t, StringArgumentType.getString(c, "source")))))))
                        .then(literal("refresh").then(targets().executes(c -> run(c, (s, r, t) -> s.skinRefresh(r, t)))))
                        .then(literal("reroll").then(targets().executes(c -> run(c, (s, r, t) -> s.skinReroll(r, t, false)))
                                .then(literal("force").executes(c -> run(c, (s, r, t) -> s.skinReroll(r, t, true))))))
                        .then(literal("lock").then(targets().executes(c -> run(c, (s, r, t) -> s.lock(r, t, true)))))
                        .then(literal("unlock").then(targets().executes(c -> run(c, (s, r, t) -> s.lock(r, t, false)))))));
    }

    private static RequiredArgumentBuilder<ServerCommandSource, ?> targets() {
        return argument("targets", EntityArgumentType.players());
    }

    private static RequiredArgumentBuilder<ServerCommandSource, String> nickname() {
        return argument("nickname", StringArgumentType.word());
    }

    private static NicksService service() throws CommandSyntaxException {
        NicksCore c = NicksRuntime.core();
        if (c == null) throw new SimpleCommandExceptionType(Text.literal("Nicks is not ready yet")).create();
        return c.service;
    }

    private static int run(CommandContext<ServerCommandSource> ctx, Action action) throws CommandSyntaxException {
        Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "targets");
        action.run(service(), Msg.reporter(ctx.getSource()), targets);
        return targets.size();
    }
}
