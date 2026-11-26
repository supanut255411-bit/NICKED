package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FictionalSkinGeneratorTest {
    private static BufferedImage decode(SkinData d) throws Exception {
        return javax.imageio.ImageIO.read(new ByteArrayInputStream(d.png()));
    }

    private static int alpha(BufferedImage i, int x, int y) { return (i.getRGB(x, y) >>> 24) & 255; }

    @Test void producesValid64x64SkinWithOpaqueBaseLayers() throws Exception {
        for (long seed = 0; seed < 60; seed++) {
            for (SkinModel m : SkinModel.values()) {
                SkinData d = FictionalSkinGenerator.generate(FictionalSkinGenerator.specFor(seed, m));
                BufferedImage img = decode(d);
                assertEquals(64, img.getWidth());
                assertEquals(64, img.getHeight());
                for (int y = 0; y < 16; y++) for (int x = 0; x < 32; x++) assertEquals(255, alpha(img, x, y), "head base opaque");
                for (int y = 16; y < 32; y++) for (int x = 0; x < 56; x++) {
                    // right arm (40..55), body, right leg: opaque except unused UV gaps
                    boolean gap = (y < 20 && (x < 4 || (x >= 12 && x < 20) || (x >= 28 && x < 36) || x >= 56)) || x >= 56;
                    boolean armGapSlim = m.slim() && x >= 54 && y >= 20;
                    if (!gap && !armGapSlim && !(y < 20 && x >= 40 && (x < 44))) {
                        if (y >= 20 || (x >= 4 && x < 12) || (x >= 20 && x < 36) || (x >= 44 && x < 56)) {
                            if (!(m.slim() && ((x >= 54 && x < 56) || (y < 20 && x >= 50)))) assertEquals(255, alpha(img, x, y), "base opaque at " + x + "," + y + " seed " + seed);
                        }
                    }
                }
            }
        }
    }

    @Test void slimSkinsLeaveFourthArmColumnTransparentAndClassicDoesNot() throws Exception {
        for (long seed = 0; seed < 40; seed++) {
            BufferedImage slim = FictionalSkinGenerator.render(FictionalSkinGenerator.specFor(seed, SkinModel.SLIM));
            BufferedImage classic = FictionalSkinGenerator.render(FictionalSkinGenerator.specFor(seed, SkinModel.CLASSIC));
            assertTrue(SkinImages.looksSlim(slim), "slim seed " + seed);
            assertFalse(SkinImages.looksSlim(classic), "classic seed " + seed);
        }
    }

    @Test void deterministicPerSeedAndModel() {
        SkinData a = FictionalSkinGenerator.generate(FictionalSkinGenerator.specFor(12345, SkinModel.SLIM));
        SkinData b = FictionalSkinGenerator.generate(FictionalSkinGenerator.specFor(12345, SkinModel.SLIM));
        assertEquals(a.hash(), b.hash());
        assertNotEquals(a.hash(), FictionalSkinGenerator.generate(FictionalSkinGenerator.specFor(12346, SkinModel.SLIM)).hash());
    }

    @Test void manySeedsGiveDistinctTexturesAndManyDistinctDesigns() {
        Set<String> hashes = new HashSet<>();
        Set<String> looks = new HashSet<>();
        for (long seed = 0; seed < 400; seed++) {
            FictionalSkinGenerator.Spec s = FictionalSkinGenerator.specFor(seed, SkinModel.CLASSIC);
            hashes.add(FictionalSkinGenerator.generate(s).hash());
            looks.add(s.outfit() + "/" + s.hair() + "/" + s.accessory());
        }
        assertEquals(400, hashes.size());
        assertTrue(looks.size() > 60, "designs should vary in outfit/hair/accessory, got " + looks.size());
    }

    @Test void defaultSkinIsValid() throws Exception {
        assertEquals(64, decode(FictionalSkinGenerator.defaultSkin(SkinModel.SLIM)).getHeight());
    }
}
