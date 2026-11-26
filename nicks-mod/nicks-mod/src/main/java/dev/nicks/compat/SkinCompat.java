package dev.nicks.compat;

import net.minecraft.entity.player.PlayerModelType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

/**
 * VERIFY ON 1.21.11: the only place that constructs SkinTextures, whose constructor changed several times:
 * <pre>
 *  1.21.2-1.21.4: new SkinTextures(Identifier tex, String url, Identifier cape, Identifier elytra, SkinTextures.Model m, boolean secure)
 *  1.21.5-1.21.8: new SkinTextures(AssetInfo.TextureAsset tex, TextureAsset cape, TextureAsset elytra, SkinTextures.Model m, boolean secure)
 *  1.21.9+     : same shape, model enum is now PlayerModelType (WIDE/SLIM)   &lt;-- written below
 * </pre>
 * The cape is deliberately dropped: showing the real player's cape on a nicknamed identity would give them away.
 * {@code id} doubles as the registered texture path because the texture is registered dynamically (not a resource file).
 */
public final class SkinCompat {
    private SkinCompat() {}

    public static SkinTextures build(Identifier texture, boolean slim, SkinTextures original) {
        return new SkinTextures(new AssetInfo.TextureAssetInfo(texture, texture), null, null,
                slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, true);
    }
}
