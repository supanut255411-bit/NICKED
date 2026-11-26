package dev.nicks.core;

/** What kind of identity a player currently displays. */
public enum IdentityType {
    /** No nickname active; the player's own name and skin. */
    ORIGINAL,
    /** Nickname verified as an existing Minecraft Java account; official skin applied. */
    REAL_ACCOUNT,
    /** Nickname confirmed not to be a Minecraft account; generated/asset skin applied. */
    FICTIONAL;

    public static IdentityType parse(String s) {
        if (s != null) for (IdentityType t : values()) if (t.name().equalsIgnoreCase(s)) return t;
        return ORIGINAL;
    }
}
