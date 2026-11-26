package dev.nicks.core;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Nickname syntax helpers. */
public final class Nicknames {
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private Nicknames() {}

    /** True if the string could be a Minecraft Java account name (3-16 chars, letters/digits/underscore). */
    public static boolean isValidAccountName(String s) {
        return s != null && ACCOUNT.matcher(s).matches();
    }

    /** Case-insensitive lookup key. */
    public static String key(String s) { return s.toLowerCase(Locale.ROOT); }

    /** Returns an error message when the nickname is not acceptable under the current config. */
    public static Optional<String> validate(String nick, NicksConfig cfg) {
        if (nick == null || nick.isBlank()) return Optional.of("Nickname must not be empty");
        if (isValidAccountName(nick)) return Optional.empty();
        if (!cfg.nickname.allowFreeFormNicknames) {
            return Optional.of("Nickname must be 3-16 characters: letters, digits or underscore");
        }
        if (nick.length() > 32) return Optional.of("Nickname is longer than 32 characters");
        for (int i = 0; i < nick.length(); i++) {
            char c = nick.charAt(i);
            if (c < 0x20 || c == '\u00a7' || c == 0x7f) return Optional.of("Nickname contains control characters");
        }
        return Optional.empty();
    }
}
