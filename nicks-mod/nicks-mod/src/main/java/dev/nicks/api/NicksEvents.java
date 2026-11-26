package dev.nicks.api;

import dev.nicks.core.PlayerIdentity;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.util.UUID;

/** Identity change events. "Before" handlers can veto by returning false; "after" handlers are notifications. */
public final class NicksEvents {
    public interface BeforeNicknameChange { boolean allow(UUID player, String oldNickname, String newNickname); }

    public interface AfterNicknameChange { void changed(PlayerIdentity before, PlayerIdentity after); }

    public interface BeforeSkinChange { boolean allow(UUID player, String skinSource); }

    public interface AfterSkinChange { void changed(PlayerIdentity before, PlayerIdentity after); }

    public interface BeforeIdentityReset { boolean allow(UUID player); }

    public interface AfterIdentityReset { void reset(PlayerIdentity after); }

    public static final Event<BeforeNicknameChange> BEFORE_NICKNAME_CHANGE = EventFactory.createArrayBacked(
            BeforeNicknameChange.class, l -> (p, o, n) -> { for (var x : l) if (!x.allow(p, o, n)) return false; return true; });
    public static final Event<AfterNicknameChange> AFTER_NICKNAME_CHANGE = EventFactory.createArrayBacked(
            AfterNicknameChange.class, l -> (b, a) -> { for (var x : l) x.changed(b, a); });
    public static final Event<BeforeSkinChange> BEFORE_SKIN_CHANGE = EventFactory.createArrayBacked(
            BeforeSkinChange.class, l -> (p, s) -> { for (var x : l) if (!x.allow(p, s)) return false; return true; });
    public static final Event<AfterSkinChange> AFTER_SKIN_CHANGE = EventFactory.createArrayBacked(
            AfterSkinChange.class, l -> (b, a) -> { for (var x : l) x.changed(b, a); });
    public static final Event<BeforeIdentityReset> BEFORE_IDENTITY_RESET = EventFactory.createArrayBacked(
            BeforeIdentityReset.class, l -> p -> { for (var x : l) if (!x.allow(p)) return false; return true; });
    public static final Event<AfterIdentityReset> AFTER_IDENTITY_RESET = EventFactory.createArrayBacked(
            AfterIdentityReset.class, l -> a -> { for (var x : l) x.reset(a); });

    private NicksEvents() {}
}
