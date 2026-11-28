package dev.nicks.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The single authoritative identity record for one player UUID. The UUID and original username describe the real
 * account and are never altered by nickname/appearance changes; everything else is the "displayed" layer on top.
 * The appearance part (skin + cape) always comes from ONE source account / borrowed skin, see {@link Appearance}.
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
        boolean defaultSkin,
        String capeHash,
        boolean capeAvailable,
        UUID officialProfileUuid,
        String sourceName,
        String textureSignature,
        long appearanceUpdated,
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
        capeHash = capeHash == null ? "" : capeHash;
        sourceName = sourceName == null ? "" : sourceName;
        textureSignature = textureSignature == null ? "" : textureSignature;
        nicknameHistory = List.copyOf(nicknameHistory == null ? List.of() : nicknameHistory);
    }

    public static PlayerIdentity initial(UUID uuid, String username, long now) {
        return new PlayerIdentity(uuid, username, null, false, IdentityType.ORIGINAL, "original", SkinModel.CLASSIC,
                "", false, "", false, null, "", "", 0, false, 0, now, now, List.of());
    }

    /** The name other players should see. */
    public String effectiveName() { return active && nickname != null ? nickname : originalUsername; }

    /** True when clients must replace this player's skin/cape (custom skin, or the source account's default skin). */
    public boolean hasAppearance() { return active && (!skinHash.isEmpty() || defaultSkin); }

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
        public boolean defaultSkin;
        public String capeHash;
        public boolean capeAvailable;
        public UUID officialProfileUuid;
        public String sourceName;
        public String textureSignature;
        public long appearanceUpdated;
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
            defaultSkin = p.defaultSkin;
            capeHash = p.capeHash;
            capeAvailable = p.capeAvailable;
            officialProfileUuid = p.officialProfileUuid;
            sourceName = p.sourceName;
            textureSignature = p.textureSignature;
            appearanceUpdated = p.appearanceUpdated;
            skinLocked = p.skinLocked;
            revision = p.revision;
            createdAt = p.createdAt;
            lastUpdated = p.lastUpdated;
            nicknameHistory = new ArrayList<>(p.nicknameHistory);
        }

        /** Replace skin AND cape together from one resolved appearance (a missing cape clears any previous cape). */
        public Builder setAppearance(IdentityType type, String source, Appearance a) {
            identityType = type;
            skinSource = source;
            skinHash = a.skin() == null ? "" : a.skin().hash();
            defaultSkin = a.skin() == null;
            skinModel = a.model();
            capeHash = a.cape() == null ? "" : a.cape().hash();
            capeAvailable = a.capeAvailable();
            officialProfileUuid = a.sourceUuid();
            sourceName = a.sourceName();
            textureSignature = a.signature();
            appearanceUpdated = a.updatedAt();
            return this;
        }

        /** Remove the displayed identity (history and lock state are kept). Original skin and cape show again. */
        public Builder clearDisplayed() {
            nickname = null;
            active = false;
            identityType = IdentityType.ORIGINAL;
            skinSource = "original";
            skinModel = SkinModel.CLASSIC;
            skinHash = "";
            defaultSkin = false;
            capeHash = "";
            capeAvailable = false;
            officialProfileUuid = null;
            sourceName = "";
            textureSignature = "";
            appearanceUpdated = 0;
            return this;
        }

        public PlayerIdentity build() {
            return new PlayerIdentity(playerUuid, originalUsername, nickname, active, identityType, skinSource, skinModel,
                    skinHash, defaultSkin, capeHash, capeAvailable, officialProfileUuid, sourceName, textureSignature,
                    appearanceUpdated, skinLocked, revision, createdAt, lastUpdated, nicknameHistory);
        }
    }
}
