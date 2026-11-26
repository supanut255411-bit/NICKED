package dev.nicks.mixin.client;

import dev.nicks.client.NicksClient;
import net.minecraft.client.network.ClientCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Convenience only: adds nicknames to the client-side player-name suggestions so /tp, /gamemode ... tab-complete them.
 * Optional (require = 0): if the method moves in a future version the mod still works, completion just lists real names.
 */
@Mixin(ClientCommandSource.class)
public abstract class ClientCommandSourceMixin {
    @Inject(method = "getPlayerNames", at = @At("RETURN"), cancellable = true, require = 0)
    private void nicks$addNicknames(CallbackInfoReturnable<Collection<String>> cir) {
        Collection<String> extra = NicksClient.registry().nicknames();
        if (extra.isEmpty()) return;
        Collection<String> all = new ArrayList<>(cir.getReturnValue());
        for (String n : extra) if (!all.contains(n)) all.add(n);
        cir.setReturnValue(all);
    }
}
