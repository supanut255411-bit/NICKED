package dev.nicks.core;

import java.util.UUID;

/**
 * One unified appearance record: the skin AND cape of ONE source account (or one borrowed real skin), resolved from the
 * same profile response so a skin can never be paired with another player's cape.
 *
 * @param sourceUuid    account UUID the textures belong to (null for skins from a skin database)
 * @param skin          null = the source account has no custom skin, so its built-in default skin applies
 * @param cape          null = the source has no cape; the target must then show NO cape (nothing invented or kept)
 * @param capeAvailable true only if the profile really had a CAPE entry
 * @param signature     Mojang's signature of the textures property (informational; textures are delivered as PNGs)
 */
public record Appearance(UUID sourceUuid, String sourceName, String sourceKind, SkinData skin, SkinModel model,
                         SkinData cape, boolean capeAvailable, String signature, long updatedAt) {
    public Appearance {
        sourceName = sourceName == null ? "" : sourceName;
        sourceKind = sourceKind == null ? "" : sourceKind;
        model = model == null ? SkinModel.CLASSIC : model;
        signature = signature == null ? "" : signature;
    }

    public boolean usesDefaultSkin() { return skin == null; }
}
