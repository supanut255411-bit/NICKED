package dev.nicks;

import dev.nicks.command.NicksCommand;
import dev.nicks.core.NicksLog;
import dev.nicks.net.IdentityNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NicksMod implements ModInitializer {
    public static final String ID = "nicks";

    @Override
    public void onInitialize() {
        Logger log = LoggerFactory.getLogger("Nicks");
        NicksLog.setSink((level, msg, t) -> {
            switch (level) {
                case "ERROR" -> log.error(msg, t);
                case "WARN" -> { if (t != null) log.warn(msg, t); else log.warn(msg); }
                case "DEBUG" -> log.info("[debug] {}", msg);
                default -> log.info(msg);
            }
        });

        IdentityNetwork.registerPayloads();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> NicksCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> NicksRuntime.setCore(NicksCore.start(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            NicksCore c = NicksRuntime.core();
            NicksRuntime.setCore(null);
            if (c != null) c.close();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            NicksCore c = NicksRuntime.core();
            if (c == null) return;
            ServerPlayerEntity p = handler.getPlayer();
            c.manager.getOrCreate(p.getUuid(), dev.nicks.compat.ProfileCompat.name(p.getGameProfile()));
            c.network.onJoin(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            NicksCore c = NicksRuntime.core();
            if (c != null) c.network.onDisconnect(handler.getPlayer().getUuid());
        });
    }
}
