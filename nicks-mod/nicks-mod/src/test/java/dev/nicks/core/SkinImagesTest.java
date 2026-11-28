package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class SkinImagesTest {
    private static byte[] png(int w, int h, int argb) {
        BufferedImage i = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) i.setRGB(x, y, argb);
        return SkinImages.encodePng(i);
    }

    @Test void legacy64x32IsConvertedWithMirroredLeftLimbs() throws Exception {
        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) legacy.setRGB(x, y, 0xFF000000 | (x * 4) << 16 | (y * 8) << 8 | 0x33);
        BufferedImage out = SkinImages.normalize(legacy);
        assertEquals(64, out.getHeight());
        // left leg front (20..23, 52..63) is the mirrored right leg front (4..7, 20..31)
        for (int i = 0; i < 12; i++) for (int j = 0; j < 4; j++) {
            assertEquals(legacy.getRGB(4 + (3 - j), 20 + i), out.getRGB(20 + j, 52 + i));
        }
        // left arm front (36..39, 52..63) mirrors right arm front (44..47, 20..31)
        for (int i = 0; i < 12; i++) for (int j = 0; j < 4; j++) {
            assertEquals(legacy.getRGB(44 + (3 - j), 20 + i), out.getRGB(36 + j, 52 + i));
        }
    }

    @Test void opaqueHatRegionOfLegacySkinBecomesTransparent() {
        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) legacy.setRGB(x, y, 0xFF336699);
        BufferedImage out = SkinImages.normalize(legacy);
        assertEquals(0, (out.getRGB(40, 8) >>> 24) & 255);
        assertEquals(255, (out.getRGB(8, 8) >>> 24) & 255);
    }

    @Test void acceptsValid64x64AndHashIsStable() throws Exception {
        SkinData a = SkinImages.fromPng(png(64, 64, 0xFF445566), SkinModel.CLASSIC, 100_000);
        SkinData b = SkinImages.fromPng(png(64, 64, 0xFF445566), SkinModel.CLASSIC, 100_000);
        assertEquals(a.hash(), b.hash());
        assertEquals(64, a.hash().length());
    }

    @Test void rejectsBadInput() {
        assertThrows(SkinException.class, () -> SkinImages.decode("not a png at all".getBytes(), 1000));
        assertThrows(SkinException.class, () -> SkinImages.decode(png(128, 128, 0xFF000000), 1_000_000));
        assertThrows(SkinException.class, () -> SkinImages.decode(png(64, 48, 0xFF000000), 1_000_000));
        assertThrows(SkinException.class, () -> SkinImages.decode(png(64, 64, 0xFF000000), 10));
        assertThrows(SkinException.class, () -> SkinImages.decode(new byte[0], 1000));
        byte[] truncated = png(64, 64, 0xFF123456);
        byte[] cut = java.util.Arrays.copyOf(truncated, truncated.length / 2);
        assertThrows(SkinException.class, () -> SkinImages.decode(cut, 1_000_000));
    }

    private static byte[] capePng(int w, int h) {
        BufferedImage i = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) i.setRGB(x, y, x < 3 ? 0x00000000 : 0xFF000000 | (x * 3) << 16 | (y * 5) << 8 | 0x44);
        return SkinImages.encodePng(i);
    }

    @Test void officialCapeSizesAreAcceptedAndPixelsIncludingTransparencyAreKept() throws Exception {
        for (int[] d : new int[][] {{64, 32}, {128, 64}, {256, 128}, {22, 17}, {46, 22}}) {
            byte[] png = capePng(d[0], d[1]);
            BufferedImage img = SkinImages.decodeCape(png, 1_000_000);
            assertEquals(d[0], img.getWidth());
            assertEquals(d[1], img.getHeight());
            assertEquals(0, (img.getRGB(0, 0) >>> 24) & 255, "transparency preserved");
            BufferedImage original = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            for (int y = 0; y < d[1]; y++) for (int x = 0; x < d[0]; x++) assertEquals(original.getRGB(x, y), img.getRGB(x, y));
        }
        assertEquals(SkinImages.fromCapePng(capePng(64, 32), 1_000_000).hash(), SkinImages.fromCapePng(capePng(64, 32), 1_000_000).hash());
    }

    @Test void badCapesAreRejected() {
        assertThrows(SkinException.class, () -> SkinImages.decodeCape(capePng(100, 100), 1_000_000));
        assertThrows(SkinException.class, () -> SkinImages.decodeCape(capePng(64, 64), 1_000_000));
        assertThrows(SkinException.class, () -> SkinImages.decodeCape("nope".getBytes(), 1_000_000));
        assertThrows(SkinException.class, () -> SkinImages.decodeCape(capePng(64, 32), 10));
        byte[] png = capePng(64, 32);
        byte[] cut = java.util.Arrays.copyOf(png, png.length / 2);
        assertThrows(SkinException.class, () -> SkinImages.decodeCape(cut, 1_000_000));
    }
}
