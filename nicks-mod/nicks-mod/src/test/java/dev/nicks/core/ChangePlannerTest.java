package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChangePlannerTest {
    private static PlayerIdentity identity(boolean locked, IdentityType type, boolean appearance) {
        PlayerIdentity.Builder b = PlayerIdentity.initial(UUID.randomUUID(), "P", 0).toBuilder();
        b.skinLocked = locked;
        b.active = true;
        b.identityType = type;
        if (appearance) b.skinHash = "a".repeat(64);
        return b.build();
    }

    @Test void lockedAppearanceIsKeptWhenTheNicknameChanges() {
        var plan = ChangePlanner.plan(identity(true, IdentityType.REAL_ACCOUNT, true), SkinMode.FROM_NICKNAME, false, true);
        assertTrue(plan.ok());
        assertEquals(SkinMode.KEEP, plan.mode(), "neither skin nor cape may change");
        assertEquals("locked.nicknameonly", plan.warning());
    }

    @Test void lockBlocksSkinSetAndRerollUnlessForced() {
        PlayerIdentity locked = identity(true, IdentityType.FICTIONAL, true);
        assertEquals("fail.locked", ChangePlanner.plan(locked, SkinMode.FROM_SOURCE, false, true).error());
        assertEquals("fail.locked", ChangePlanner.plan(locked, SkinMode.REROLL, false, true).error());
        assertTrue(ChangePlanner.plan(locked, SkinMode.REROLL, true, true).ok());
        assertTrue(ChangePlanner.plan(locked, SkinMode.FROM_SOURCE, false, false).ok(), "locking disabled in the config");
    }

    @Test void unlockedChangesPassThroughUnchanged() {
        PlayerIdentity free = identity(false, IdentityType.REAL_ACCOUNT, true);
        assertEquals(SkinMode.FROM_NICKNAME, ChangePlanner.plan(free, SkinMode.FROM_NICKNAME, false, true).mode());
        assertEquals(SkinMode.FROM_SOURCE, ChangePlanner.plan(free, SkinMode.FROM_SOURCE, false, true).mode());
        assertEquals(SkinMode.KEEP, ChangePlanner.plan(free, SkinMode.KEEP, false, true).mode());
    }

    @Test void refreshNeedsAnAppearanceAndRerollNeedsAFictionalIdentity() {
        assertEquals("fail.norefresh", ChangePlanner.plan(identity(false, IdentityType.ORIGINAL, false), SkinMode.REFRESH, false, true).error());
        assertEquals("fail.fictional.only", ChangePlanner.plan(identity(false, IdentityType.REAL_ACCOUNT, true), SkinMode.REROLL, false, true).error());
        assertTrue(ChangePlanner.plan(identity(false, IdentityType.REAL_ACCOUNT, true), SkinMode.REROLL, true, true).ok());
        assertTrue(ChangePlanner.plan(identity(false, IdentityType.REAL_ACCOUNT, true), SkinMode.REFRESH, false, true).ok());
    }
}
