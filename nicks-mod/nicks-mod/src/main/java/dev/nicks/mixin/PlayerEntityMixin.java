package dev.nicks.mixin;

import dev.nicks.NicksRuntime;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The single hook behind chat, death/advancement/join/leave messages, /msg, team chat and (on the client) the nametag:
 * all of them are built from {@code getDisplayName()}, which decorates {@code getName()} with team prefix/suffix/colour
 * and the click-to-whisper event. Only the displayed Text changes; the GameProfile, UUID and signed-chat data do not.
 *
 * VERIFY ON 1.21.11 (Yarn): PlayerEntity declares {@code Text getName()}. If injection fails at startup the
 * descriptor changed; the mixin is required=1 on purpose so a mismatch is loud, never silent.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void nicks$nickname(CallbackInfoReturnable<Text> cir) {
        Text nick = NicksRuntime.nameFor((PlayerEntity) (Object) this);
        if (nick != null) cir.setReturnValue(nick);
    }
}
