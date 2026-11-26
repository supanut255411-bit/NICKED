package dev.nicks.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The single authoritative identity record for one player UUID.
 * The UUID and original username are never altered by nickname/skin changes; they only describe the
 * real account. Everything else is the "displayed" layer on top.
 */
public record PlayerIdentity(
        UUID playerUuid,
        String originalUsername,
        String nickname,
        boolean active,
        IdentityType identityType,
        String skinSource,
        SkinModel skinModel,
        String skinHash,
        String fictionalSkinId,
        UUID officialProfileUuid,
        boolean skinLocked,
        long revision,
        long createdAt,
        long lastUpdated,
        List<String> nicknameHistory) {

    public PlayerIdentity {
        Objects.requireNonNull(playerUuid, "playerUuid");
        originalUsername = originalUsername == null ? "" : originalUsername;
        identityType = identityType == null ? IdentityType.ORIGINAL : identityType;
        skinSource = skinSource == null ? "original" : skinSource;
        skinModel = skinModel == null ? SkinModel.CLASSIC : skinModel;
        skinHash = skinHash == null ? "" : skinHash;
        fictionalSkinId = fictionalSkinId == null ? "" : fictionalSkinId;
        nicknameHistory = List.copyOf(nicknameHistory == null ? List.of() : nicknameHistory);
    }

    public static PlayerIdentity initial(UUID uuid, String username, long now) {
        return new PlayerIdentity(uuid, username, null, false, IdentityType.ORIGINAL, "original",
                SkinModel.CLASSIC, "", "", null, false, 0, now, now, List.of());
    }

    /** The name other players should see. */
    public String effectiveName() { return active && nickname != null ? nickname : originalUsername; }

    /** True when a custom skin override should be rendered by clients. */
    public boolean hasSkinOverride() { return active && !skinHash.isEmpty(); }

    public Builder toBuilder() { return new Builder(this); }

    /** Mutable copy used inside {@link PlayerIdentityManager#apply}. */
    public static final class Builder {
        public final UUID playerUuid;
        public String originalUsername;
        public String nickname;
        public boolean active;
        public IdentityType identityType;
        public String skinSource;
        public SkinModel skinModel;
        public String skinHash;
        public String fictionalSkinId;
        public UUID officialProfileUuid;
        public boolean skinLocked;
        public long revision;
        public long createdAt;
        public long lastUpdated;
        public final List<String> nicknameHistory;

        Builder(PlayerIdentity p) {
            playerUuid = p.playerUuid;
            originalUsername = p.originalUsername;
            nickname = p.nickname;
            active = p.active;
            identityType = p.identityType;
            skinSource = p.skinSource;
            skinModel = p.skinModel;
            skinHash = p.skinHash;
            fictionalSkinId = p.fictionalSkinId;
            officialProfileUuid = p.officialProfileUuid;
            skinLocked = p.skinLocked;
            revision = p.revision;
            createdAt = p.createdAt;
            lastUpdated = p.lastUpdated;
            nicknameHistory = new ArrayList<>(p.nicknameHistory);
        }

        /** Remove the displayed identity (history and lock state are kept). */
        public Builder clearDisplayed() {
            nickname = null;
            active = false;
            identityType = IdentityType.ORIGINAL;
            skinSource = "original";
            skinModel = SkinModel.CLASSIC;
            skinHash = "";
            fictionalSkinId = "";
            officialProfileUuid = null;
            return this;
        }

        public PlayerIdentity build() {
            return new PlayerIdentity(playerUuid, originalUsername, nickname, active, identityType, skinSource,
                    skinModel, skinHash, fictionalSkinId, officialProfileUuid, skinLocked, revision, createdAt,
                    lastUpdated, nicknameHistory);
        }
    }
}
