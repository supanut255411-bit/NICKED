package dev.nicks.compat;

import com.mojang.authlib.GameProfile;

import java.util.UUID;

/**
 * VERIFY ON 1.21.11: GameProfile is a record in recent authlib (name()/id()); older versions use getName()/getId().
 * Everything that needs a profile's name or UUID goes through here.
 */
public final class ProfileCompat {
    private ProfileCompat() {}

    public static String name(GameProfile p) { return p.name(); } // older: p.getName()

    public static UUID id(GameProfile p) { return p.id(); }       // older: p.getId()
}
