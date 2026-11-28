package dev.nicks.core;

import java.util.UUID;

/** Outcome of asking Mojang whether a name is a real Java account. Three distinct outcomes by design. */
public sealed interface ProfileLookupResult {
    /**
     * Account exists. {@code skinUrl}/{@code capeUrl} are null when the profile has no SKIN / no CAPE entry
     * (a missing SKIN means the account's built-in default skin applies).
     */
    record Real(UUID profileUuid, String canonicalName, String skinUrl, SkinModel model, String capeUrl, String signature)
            implements ProfileLookupResult {}

    /** The profile service answered and confirmed the account does not exist. */
    record NotFound() implements ProfileLookupResult {}

    /** Network error, timeout, rate limit, malformed response: NOT a statement about the account. */
    record Unresolved(String reason) implements ProfileLookupResult {}
}
