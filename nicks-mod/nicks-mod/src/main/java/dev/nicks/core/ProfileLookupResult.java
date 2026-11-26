package dev.nicks.core;

import java.util.UUID;

/** Outcome of asking Mojang whether a name is a real Java account. Three distinct outcomes by design. */
public sealed interface ProfileLookupResult {
    /** Account exists. {@code skinUrl} is null when the account uses a default skin. */
    record Real(UUID profileUuid, String canonicalName, String skinUrl, SkinModel model) implements ProfileLookupResult {}

    /** The profile service answered and confirmed the account does not exist. */
    record NotFound() implements ProfileLookupResult {}

    /** Network error, timeout, rate limit, malformed response: NOT a statement about the account. */
    record Unresolved(String reason) implements ProfileLookupResult {}
}
