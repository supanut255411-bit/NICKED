package dev.nicks.core;

/** Pure decision logic: what may happen to the appearance given the lock state and the request. */
public final class ChangePlanner {
    /** @param error message key when refused, @param warning message key to show but continue */
    public record Plan(SkinMode mode, String error, String warning) {
        public boolean ok() { return error == null; }
    }

    private ChangePlanner() {}

    public static Plan plan(PlayerIdentity cur, SkinMode requested, boolean force, boolean lockingEnabled) {
        boolean locked = cur.skinLocked() && lockingEnabled;
        // a locked appearance (skin AND cape) is never replaced by nickname commands: only the name changes
        if (locked && requested == SkinMode.FROM_NICKNAME) return new Plan(SkinMode.KEEP, null, "locked.nicknameonly");
        if (locked && (requested == SkinMode.FROM_SOURCE || requested == SkinMode.REROLL) && !force) {
            return new Plan(requested, "fail.locked", null);
        }
        if (requested == SkinMode.REFRESH && !cur.hasAppearance()) return new Plan(requested, "fail.norefresh", null);
        if (requested == SkinMode.REROLL && cur.identityType() != IdentityType.FICTIONAL && !force) {
            return new Plan(requested, "fail.fictional.only", null);
        }
        return new Plan(requested, null, null);
    }
}
