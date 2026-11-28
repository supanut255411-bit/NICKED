package dev.nicks.client;

import dev.nicks.NicksRuntime;
import dev.nicks.net.IdentitySyncPayload;
import dev.nicks.net.SkinTexturePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class NicksClient implements ClientModInitializer {
    private static final ClientIdentityRegistry REGISTRY = new ClientIdentityRegistry();

    public static ClientIdentityRegistry registry() { return REGISTRY; }

    @Override
    public void onInitializeClient() {
        NicksRuntime.setClientView(REGISTRY);
        // Textures always arrive before the identity that references them (same connection, in order).
        ClientPlayNetworking.registerGlobalReceiver(SkinTexturePayload.ID,
                (payload, ctx) -> ctx.client().execute(() -> REGISTRY.onTexture(payload.kind(), payload.hash(), payload.png())));
        ClientPlayNetworking.registerGlobalReceiver(IdentitySyncPayload.ID,
                (payload, ctx) -> ctx.client().execute(() -> REGISTRY.onIdentity(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(REGISTRY::clear));
    }
}
