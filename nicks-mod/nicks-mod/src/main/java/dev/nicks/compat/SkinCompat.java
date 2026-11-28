package dev.nicks.compat;

import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.PlayerModelType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * VERIFY ON 1.21.11: the only place that constructs SkinTextures, whose constructor changed several times:
 * <pre>
 *  1.21.2-1.21.4: new SkinTextures(Identifier tex, String url, Identifier cape, Identifier elytra, SkinTextures.Model m, boolean secure)
 *  1.21.5-1.21.8: new SkinTextures(AssetInfo.TextureAsset tex, TextureAsset cape, TextureAsset elytra, SkinTextures.Model m, boolean secure)
 *  1.21.9+     : same shape, model enum is now PlayerModelType (WIDE/SLIM)   &lt;-- written below
 * </pre>
 * Also verify {@code DefaultSkinHelper.getSkinTextures(UUID)} (the built-in default skin the game picks for a UUID) and the
 * record accessor {@code texture()} used in the rare "default skin + cape" case.
 *
 * The textures are registered dynamically, so the registered Identifier doubles as the texture path. The cape (null =
 * the source has none, so NO cape is shown) also serves as elytra texture, exactly as vanilla does for profile capes.
 */
public final class SkinCompat {
    private SkinCompat() {}

    /**
     * @param skin          registered skin texture, or null to use the built-in default skin of {@code defaultSource}
     * @param cape          registered cape texture, or null for no cape
     * @param defaultSource account whose default skin applies when {@code skin} is null
     */
    public static SkinTextures build(Identifier skin, Identifier cape, boolean slim, UUID defaultSource) {
        if (skin == null) {
            SkinTextures base = DefaultSkinHelper.getSkinTextures(defaultSource);
            if (cape == null) return base; // default skins carry no cape
            AssetInfo.TextureAsset capeAsset = new AssetInfo.TextureAssetInfo(cape, cape);
            return new SkinTextures(base.texture(), capeAsset, capeAsset, base.model(), true);
        }
        AssetInfo.TextureAsset body = new AssetInfo.TextureAssetInfo(skin, skin);
        AssetInfo.TextureAsset capeAsset = cape == null ? null : new AssetInfo.TextureAssetInfo(cape, cape);
        return new SkinTextures(body, capeAsset, capeAsset, slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, true);
    }
}
