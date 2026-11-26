package dev.nicks.mixin;

import dev.nicks.NicksRuntime;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla commands resolve a literal player name through {@code PlayerManager#getPlayer(String)}. Falling back to the
 * nickname registry here makes every vanilla command that takes a player argument accept nicknames, without rewriting
 * any command text. Coordinates, UUIDs, selectors and other arguments never reach this method.
 *
 * VERIFY ON 1.21.11 (Yarn): {@code ServerPlayerEntity getPlayer(String name)}.
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Inject(method = "getPlayer(Ljava/lang/String;)Lnet/minecraft/server/network/ServerPlayerEntity;", at = @At("RETURN"), cancellable = true)
    private void nicks$resolveNickname(String name, CallbackInfoReturnable<ServerPlayerEntity> cir) {
        ServerPlayerEntity found = cir.getReturnValue();
        ServerPlayerEntity adjusted = NicksRuntime.adjustLookup(name, found);
        if (adjusted != found) cir.setReturnValue(adjusted);
    }
}
