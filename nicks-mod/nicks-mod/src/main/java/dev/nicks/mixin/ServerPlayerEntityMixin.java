package dev.nicks.mixin;

import dev.nicks.NicksRuntime;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tab list: vanilla builds every PlayerListS2CPacket entry from {@code getPlayerListName()} (null = use the profile
 * name). Returning the decorated nickname there replaces the name for ADD_PLAYER (joins) and UPDATE_DISPLAY_NAME
 * (identity changes) without touching the profile, UUID, ping, game mode or listed flag.
 *
 * VERIFY ON 1.21.11 (Yarn): {@code @Nullable Text getPlayerListName()}.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "getPlayerListName", at = @At("HEAD"), cancellable = true)
    private void nicks$tabName(CallbackInfoReturnable<Text> cir) {
        Text name = NicksRuntime.listNameFor((ServerPlayerEntity) (Object) this);
        if (name != null) cir.setReturnValue(name);
    }
}
