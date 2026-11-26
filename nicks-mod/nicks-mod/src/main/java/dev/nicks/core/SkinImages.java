package dev.nicks.core;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

/** Validation, vanilla-style normalization (legacy 64x32 conversion, alpha rules), hashing and PNG encoding. */
public final class SkinImages {
    static { System.setProperty("java.awt.headless", "true"); }

    private SkinImages() {}

    /** Decode a PNG, checking magic, size limit and dimensions BEFORE allocating the pixel buffer. */
    public static BufferedImage decode(byte[] data, int maxBytes) throws SkinException {
        if (data == null || data.length == 0) throw new SkinException("Empty texture");
        if (data.length > maxBytes) throw new SkinException("Texture too large (" + data.length + " bytes)");
        if (data.length < 8 || (data[0] & 0xFF) != 0x89 || data[1] != 'P' || data[2] != 'N' || data[3] != 'G') {
            throw new SkinException("Texture is not a PNG");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("png");
            if (!it.hasNext()) throw new SkinException("No PNG reader available on this JVM (java.desktop missing?)");
            ImageReader reader = it.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w != 64 || (h != 64 && h != 32)) throw new SkinException("Unsupported skin size " + w + "x" + h);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new SkinException("Malformed PNG: " + e.getMessage());
        }
    }

    /** Returns a 64x64 ARGB image following the vanilla skin-processing rules. */
    public static BufferedImage normalize(BufferedImage src) {
        boolean legacy = src.getHeight() == 32;
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < Math.min(64, src.getHeight()); y++) {
            for (int x = 0; x < 64; x++) out.setRGB(x, y, src.getRGB(x, y));
        }
        if (legacy) {
            // mirror right leg/arm onto the left ones (same rectangles vanilla's legacy converter copies)
            copyRect(out, 4, 16, 16, 32, 4, 4);
            copyRect(out, 8, 16, 16, 32, 4, 4);
            copyRect(out, 0, 20, 24, 32, 4, 12);
            copyRect(out, 4, 20, 16, 32, 4, 12);
            copyRect(out, 8, 20, 8, 32, 4, 12);
            copyRect(out, 12, 20, 16, 32, 4, 12);
            copyRect(out, 44, 16, -8, 32, 4, 4);
            copyRect(out, 48, 16, -8, 32, 4, 4);
            copyRect(out, 40, 20, 0, 32, 4, 12);
            copyRect(out, 44, 20, -8, 32, 4, 12);
            copyRect(out, 48, 20, -16, 32, 4, 12);
            copyRect(out, 52, 20, -8, 32, 4, 12);
        }
        setNoAlpha(out, 0, 0, 32, 16);
        if (legacy) notchHack(out, 32, 0, 64, 32);
        setNoAlpha(out, 0, 16, 64, 32);
        setNoAlpha(out, 16, 48, 48, 64);
        return out;
    }

    private static void copyRect(BufferedImage img, int x, int y, int dx, int dy, int w, int h) {
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                int src = img.getRGB(x + (w - 1 - j), y + i); // mirrored in X
                img.setRGB(x + dx + j, y + dy + i, src);
            }
        }
    }

    private static void setNoAlpha(BufferedImage img, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) img.setRGB(x, y, img.getRGB(x, y) | 0xFF000000);
    }

    /** Old skins without hat transparency: make the hat layer transparent, as vanilla does. */
    private static void notchHack(BufferedImage img, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (((img.getRGB(x, y) >>> 24) & 0xFF) < 128) return;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) img.setRGB(x, y, img.getRGB(x, y) & 0x00FFFFFF);
    }

    /**
     * Heuristic for asset-library PNGs: slim (Alex) skins leave the right arm's 4th column (x=54..55, y=20..31)
     * fully transparent. Must be called on the RAW decoded image: {@link #normalize} forces that area opaque.
     */
    public static boolean looksSlim(BufferedImage raw) {
        if (raw.getHeight() != 64) return false; // legacy 64x32 skins are always classic
        for (int y = 20; y < 32; y++) for (int x = 54; x < 56; x++) if (((raw.getRGB(x, y) >>> 24) & 0xFF) != 0) return false;
        return true;
    }

    public static String hash(BufferedImage img) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer bb = ByteBuffer.allocate(8 + 4 * img.getWidth() * img.getHeight());
            bb.putInt(img.getWidth()).putInt(img.getHeight());
            for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) bb.putInt(img.getRGB(x, y));
            return hex(md.digest(bb.array()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodePng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static SkinData toSkinData(BufferedImage normalized, SkinModel model) {
        return new SkinData(hash(normalized), model, encodePng(normalized));
    }

    /** decode + normalize + hash in one go. */
    public static SkinData fromPng(byte[] png, SkinModel model, int maxBytes) throws SkinException {
        return toSkinData(normalize(decode(png, maxBytes)), model);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(Character.forDigit((v >> 4) & 15, 16)).append(Character.forDigit(v & 15, 16));
        return sb.toString();
    }
}
