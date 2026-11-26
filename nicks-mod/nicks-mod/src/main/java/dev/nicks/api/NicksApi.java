package dev.nicks.api;

import dev.nicks.NicksCore;
import dev.nicks.NicksRuntime;
import dev.nicks.core.IdentityType;
import dev.nicks.core.PlayerIdentity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public API for other Fabric mods. Reads come straight from the central identity manager; writes go through the
 * same validation, permission-independent safety checks, events and synchronisation as /nicks (they are NOT a way to
 * bypass conflict, lock or format rules). Writes require the player to be online and complete on the server thread.
 */
public final class NicksApi {
    public record Result(boolean success, String message) {}

    /** A skin supplied by another mod. The PNG is validated like any other texture. */
    public record ProvidedSkin(byte[] png, boolean slim) {}

    public interface SkinProvider { Optional<ProvidedSkin> resolve(String id); }

    private static final Map<String, SkinProvider> PROVIDERS = new ConcurrentHashMap<>();

    private NicksApi() {}

    public static void registerSkinProvider(String prefix, SkinProvider provider) {
        PROVIDERS.put(prefix.toLowerCase(), provider);
    }

    public static SkinProvider skinProvider(String prefix) { return PROVIDERS.get(prefix.toLowerCase()); }

    // ---- reads ----------------------------------------------------------

    public static Optional<PlayerIdentity> getIdentity(UUID player) {
        NicksCore c = NicksRuntime.core();
        return c == null ? Optional.empty() : c.manager.get(player);
    }

    public static Optional<String> getOriginalUsername(UUID player) {
        return getIdentity(player).map(PlayerIdentity::originalUsername).filter(s -> !s.isEmpty());
    }

    public static Optional<String> getNickname(UUID player) {
        return getIdentity(player).filter(PlayerIdentity::active).map(PlayerIdentity::nickname);
    }

    /** Nickname if one is active, otherwise the original username (never null). */
    public static Text getDisplayName(UUID player) {
        NicksCore c = NicksRuntime.core();
        Text t = c == null ? null : c.formatter.nicknameFor(player);
        return t != null ? t : Text.literal(getOriginalUsername(player).orElse(""));
    }

    public static Optional<String> getSkinSource(UUID player) { return getIdentity(player).map(PlayerIdentity::skinSource); }

    public static Optional<IdentityType> getIdentityType(UUID player) { return getIdentity(player).map(PlayerIdentity::identityType); }

    // ---- writes ---------------------------------------------------------

    public static CompletableFuture<Result> setNickname(UUID player, String nickname) {
        NicksCore c = NicksRuntime.core();
        return c == null ? notReady() : c.service.apiSetNickname(player, nickname);
    }

    /** @param source an account name, or "prefix:id" for a registered {@link SkinProvider} */
    public static CompletableFuture<Result> setSkin(UUID player, String source) {
        NicksCore c = NicksRuntime.core();
        return c == null ? notReady() : c.service.apiSetSkin(player, source);
    }

    public static CompletableFuture<Result> resetIdentity(UUID player) {
        NicksCore c = NicksRuntime.core();
        return c == null ? notReady() : c.service.apiReset(player);
    }

    private static CompletableFuture<Result> notReady() {
        return CompletableFuture.completedFuture(new Result(false, "Nicks is not running"));
    }
}
