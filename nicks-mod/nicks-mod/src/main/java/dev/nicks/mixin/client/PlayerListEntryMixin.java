package dev.nicks.mixin.client;

import com.mojang.authlib.GameProfile;
import dev.nicks.client.NicksClient;
import dev.nicks.compat.ProfileCompat;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One hook for every place a player's skin is read: world rendering (remote players, first person, spectator view),
 * the tab list head and chat heads all go through PlayerListEntry#getSkinTextures. The renderer reads it every frame,
 * so a new identity shows on the next frame: no respawn, reconnect or dimension change. The player's profile UUID is
 * only used as the lookup key and is never modified.
 *
 * VERIFY ON 1.21.11 (Yarn): {@code SkinTextures getSkinTextures()} and {@code GameProfile getProfile()}.
 */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Shadow
    public abstract GameProfile getProfile();

    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true)
    private void nicks$skin(CallbackInfoReturnable<SkinTextures> cir) {
        SkinTextures override = NicksClient.registry().skinFor(ProfileCompat.id(getProfile()), cir.getReturnValue());
        if (override != null) cir.setReturnValue(override);
    }
}
