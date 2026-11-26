package dev.nicks.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * VERIFY ON 1.21.11: dynamic texture registration. The NativeImageBackedTexture constructor changed with the 1.21.5
 * GPU-abstraction rewrite (it now takes a debug label), so the constructor is looked up by signature. Everything else
 * (NativeImage.read, TextureManager.registerTexture/destroyTexture) is stable.
 */
public final class TextureCompat {
    private TextureCompat() {}

    public static void register(Identifier id, byte[] png) throws IOException {
        NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, create(id.toString(), image));
    }

    /** Frees the GPU texture. Call only when no identity references it any more. */
    public static void release(Identifier id) {
        MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
    }

    private static NativeImageBackedTexture create(String label, NativeImage image) {
        try {
            return NativeImageBackedTexture.class.getConstructor(Supplier.class, NativeImage.class)
                    .newInstance((Supplier<String>) () -> label, image);
        } catch (ReflectiveOperationException ignored) { }
        try {
            return NativeImageBackedTexture.class.getConstructor(String.class, NativeImage.class).newInstance(label, image);
        } catch (ReflectiveOperationException ignored) { }
        try {
            return NativeImageBackedTexture.class.getConstructor(NativeImage.class).newInstance(image);
        } catch (ReflectiveOperationException e) {
            image.close();
            throw new IllegalStateException("No supported NativeImageBackedTexture constructor found (update TextureCompat)", e);
        }
    }
}
