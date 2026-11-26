package dev.nicks.core;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

/**
 * Procedural generator for valid 64x64 Minecraft skins (correct UV layout, base + overlay layers, classic or slim
 * arms). Characters are composed from outfit templates, hair styles, accessories, face variants, procedurally drawn
 * emblems and CONTINUOUS colours (hue/saturation/brightness are drawn from the seed, not from a fixed palette), so
 * the design space is effectively unbounded: every 64-bit seed is a different character. Fully deterministic per
 * (seed, model): the same seed always renders the same pixels.
 */
public final class FictionalSkinGenerator {
    public enum Outfit { TSHIRT, HOODIE, JACKET, SPORTS, SCHOOL, MEDIEVAL, FANTASY, MODERN }
    public enum Hair { BUZZ, SHORT, SPIKY, LONG, BOB, MESSY, BEANIE }
    public enum Accessory { NONE, GLASSES, HEADBAND, SCARF, BEARD }

    private static final int WHITE = 0xECECEC, GOLD = 0xD4AF37, LEATHER = 0x6B4A2B, BOOT = 0x3E2A18, BELT = 0x4B3621;
    private static final int[] NATURAL_HAIR = {0x151515, 0x2B1B10, 0x4A2E17, 0x7A4A21, 0x8B3A1E, 0xB5541F, 0xE3C16F,
            0xEEE3B0, 0x9A9A9A, 0xE8E8E8};
    private static final int[][] DIGITS = {
            {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
            {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 1, 1, 1}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7}};

    /** Everything needed to reproduce a skin. Colours are plain 0xRRGGBB values. */
    public record Spec(long seed, SkinModel model, Outfit outfit, Hair hair, Accessory accessory, int skin,
                       int hairColor, int primary, int secondary, int pants, int shoes, int eyes, int variant, long emblem) {
        /** Coarse look signature (~10^10 combinations) used to keep assigned skins visually different. */
        public String visualKey() {
            return outfit + "/" + hair + "/" + accessory + "/" + q(primary, 36, 3, 3) + "/" + q(secondary, 12, 1, 1)
                    + "/" + q(hairColor, 12, 1, 3) + "/" + q(skin, 1, 1, 6) + "/" + q(pants, 1, 1, 4) + "/" + (variant % 12);
        }
    }

    private FictionalSkinGenerator() {}

    public static Spec specFor(long seed, SkinModel model) {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + 0x5DEECE66DL);
        Outfit o = Outfit.values()[r.nextInt(Outfit.values().length)];
        Hair h = Hair.values()[r.nextInt(Hair.values().length)];
        Accessory a = r.nextInt(10) < 4 ? Accessory.NONE : Accessory.values()[1 + r.nextInt(Accessory.values().length - 1)];
        int skin = skinTone(r);
        int hair = hairColor(r);
        float ph = r.nextFloat();
        int primary = hsv(ph, 0.35f + r.nextFloat() * 0.6f, 0.35f + r.nextFloat() * 0.6f);
        float sh = switch (r.nextInt(3)) {
            case 0 -> ph + 0.5f;
            case 1 -> ph + (r.nextBoolean() ? 0.083f : -0.083f);
            default -> r.nextFloat();
        };
        int secondary = hsv(sh - (float) Math.floor(sh), 0.25f + r.nextFloat() * 0.7f, 0.3f + r.nextFloat() * 0.65f);
        if (Math.abs(lum(primary) - lum(secondary)) < 40) secondary = shade(secondary, lum(primary) > 128 ? -70 : 70);
        int pants = hsv(r.nextFloat(), r.nextFloat() * 0.5f, 0.2f + r.nextFloat() * 0.4f);
        int shoes = hsv(r.nextFloat(), r.nextFloat() * 0.5f, 0.1f + r.nextFloat() * 0.8f);
        int eyes = hsv(0.04f + r.nextFloat() * 0.55f, 0.3f + r.nextFloat() * 0.5f, 0.3f + r.nextFloat() * 0.4f);
        return new Spec(seed, model, o, h, a, skin, hair, primary, secondary, pants, shoes, eyes, r.nextInt(100_000), r.nextLong());
    }

    public static SkinData generate(Spec spec) {
        return SkinImages.toSkinData(SkinImages.normalize(render(spec)), spec.model());
    }

    /** Steve/Alex-like skin used when an official account has no custom skin. */
    public static SkinData defaultSkin(SkinModel model) {
        return generate(new Spec(0, model, Outfit.TSHIRT, Hair.SHORT, Accessory.NONE, 0xBC8D6E, 0x4A2E17, 0x00A8A8,
                0xECECEC, 0x322F7A, 0x6B6B6B, 0x2B2B8A, 2, 0));
    }

    // ---- colour helpers ---------------------------------------------------

    private static int skinTone(Random r) {
        if (r.nextInt(100) < 4) return hsv(r.nextFloat(), 0.2f + r.nextFloat() * 0.4f, 0.6f + r.nextFloat() * 0.35f); // fantasy
        int base = mix(0xFFDFC4, 0x4A2E1E, r.nextFloat());
        return mix(base, 0xD9A079, r.nextFloat() * 0.3);
    }

    private static int hairColor(Random r) {
        if (r.nextInt(100) < 30) return hsv(r.nextFloat(), 0.5f + r.nextFloat() * 0.4f, 0.5f + r.nextFloat() * 0.45f); // dyed
        int a = NATURAL_HAIR[r.nextInt(NATURAL_HAIR.length)], b = NATURAL_HAIR[r.nextInt(NATURAL_HAIR.length)];
        return mix(a, b, r.nextFloat() * 0.5);
    }

    private static int hsv(float h, float s, float v) {
        return Color.HSBtoRGB(h - (float) Math.floor(h), Math.min(1f, Math.max(0f, s)), Math.min(1f, Math.max(0f, v))) & 0xFFFFFF;
    }

    private static String q(int rgb, int hueBuckets, int satBuckets, int valBuckets) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, null);
        return Math.min(hueBuckets - 1, (int) (hsb[0] * hueBuckets)) + "." + Math.min(satBuckets - 1, (int) (hsb[1] * satBuckets))
                + "." + Math.min(valBuckets - 1, (int) (hsb[2] * valBuckets));
    }

    private static int shade(int rgb, int d) {
        int r = clamp(((rgb >> 16) & 255) + d), g = clamp(((rgb >> 8) & 255) + d), b = clamp((rgb & 255) + d);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int lum(int rgb) { return (((rgb >> 16) & 255) * 3 + ((rgb >> 8) & 255) * 6 + (rgb & 255)) / 10; }

    // ---- canvas -----------------------------------------------------------

    private record Box(int ox, int oy, int w, int h, int d) {
        int topX() { return ox + d; }
        int bottomX() { return ox + d + w; }
        int sideY() { return oy + d; }
        int rightX() { return ox; }
        int frontX() { return ox + d; }
        int leftX() { return ox + d + w; }
        int backX() { return ox + 2 * d + w; }
    }

    private static final class Canvas {
        final BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        final Random rnd;
        final int noise;

        Canvas(long seed, int noise) { rnd = new Random(seed); this.noise = noise; }

        void px(int x, int y, int rgb) {
            if (x >= 0 && y >= 0 && x < 64 && y < 64) img.setRGB(x, y, 0xFF000000 | rgb);
        }

        void clear(int x, int y, int w, int h) {
            for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) img.setRGB(x + i, y + j, 0);
        }

        void fill(int x, int y, int w, int h, int rgb, int n) {
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) px(x + i, y + j, n == 0 ? rgb : shade(rgb, rnd.nextInt(2 * n + 1) - n));
            }
        }
    }

    private static int[] rows(int color) {
        int[] a = new int[12];
        Arrays.fill(a, color);
        return a;
    }

    private static void range(int[] a, int from, int to, int color) {
        for (int i = from; i <= to; i++) a[i] = color;
    }

    private static void ring(Canvas c, Box b, int from, int to, int color, int noise) {
        for (int r = from; r <= to; r++) {
            c.fill(b.rightX(), b.sideY() + r, b.d(), 1, color, noise);
            c.fill(b.frontX(), b.sideY() + r, b.w(), 1, color, noise);
            c.fill(b.leftX(), b.sideY() + r, b.d(), 1, color, noise);
            c.fill(b.backX(), b.sideY() + r, b.w(), 1, color, noise);
        }
    }

    private static void solid(Canvas c, Box b, int[] rows) {
        for (int r = 0; r < b.h(); r++) ring(c, b, r, r, rows[r], c.noise);
        c.fill(b.topX(), b.oy(), b.w(), b.d(), rows[0], Math.max(1, c.noise - 1));
        c.fill(b.bottomX(), b.oy(), b.w(), b.d(), rows[b.h() - 1], Math.max(1, c.noise - 1));
    }

    private static void fillBox(Canvas c, Box b, int color, int noise) {
        ring(c, b, 0, b.h() - 1, color, noise);
        c.fill(b.topX(), b.oy(), b.w(), b.d(), color, noise);
        c.fill(b.bottomX(), b.oy(), b.w(), b.d(), color, noise);
    }

    private static void fp(Canvas c, Box b, int x, int row, int rgb) { c.px(b.frontX() + x, b.sideY() + row, rgb); }

    private static void bp(Canvas c, Box b, int x, int row, int rgb) { c.px(b.backX() + x, b.sideY() + row, rgb); }

    private static void digit(Canvas c, Box b, int d, int x0, int row0, int color) {
        for (int r = 0; r < 5; r++) for (int col = 0; col < 3; col++) {
            if (((DIGITS[d][r] >> (2 - col)) & 1) != 0) bp(c, b, x0 + col, row0 + r, color);
        }
    }

    /** Left-right symmetric emblem: {@code half} columns x {@code rows} rows of seed bits, mirrored around the centre. */
    private static void glyph(Canvas c, Box b, int x0, int row0, int half, int rows, long bits, int color, boolean back) {
        long mask = (1L << (half * rows)) - 1;
        long g = bits & mask;
        if (Long.bitCount(g) < 3) g |= 0b10101L & mask; // never an empty or one-pixel emblem
        for (int r = 0; r < rows; r++) for (int col = 0; col < half; col++) {
            if (((g >> (r * half + col)) & 1) == 0) continue;
            int xa = x0 + col, xb = x0 + 2 * half - 1 - col;
            if (back) { bp(c, b, xa, row0 + r, color); bp(c, b, xb, row0 + r, color); }
            else { fp(c, b, xa, row0 + r, color); fp(c, b, xb, row0 + r, color); }
        }
    }

    // ---- rendering --------------------------------------------------------

    public static BufferedImage render(Spec s) {
        Canvas c = new Canvas(s.seed() * 31 + 7, 2 + (int) ((s.emblem() >>> 60) & 3)); // cloth grain 2..5
        int aw = s.model().slim() ? 3 : 4;
        Box head = new Box(0, 0, 8, 8, 8), hat = new Box(32, 0, 8, 8, 8);
        Box body = new Box(16, 16, 8, 12, 4), jacket = new Box(16, 32, 8, 12, 4);
        Box rArm = new Box(40, 16, aw, 12, 4), rSleeve = new Box(40, 32, aw, 12, 4);
        Box lArm = new Box(32, 48, aw, 12, 4), lSleeve = new Box(48, 48, aw, 12, 4);
        Box rLeg = new Box(0, 16, 4, 12, 4), rPants = new Box(0, 32, 4, 12, 4);
        Box lLeg = new Box(16, 48, 4, 12, 4), lPants = new Box(0, 48, 4, 12, 4);

        int skin = s.skin(), P = s.primary(), S = s.secondary(), T = s.pants(), H = s.shoes(), v = s.variant();
        long em = s.emblem();
        int[] torso = rows(skin), arm = rows(skin), leg = rows(skin);

        switch (s.outfit()) {
            case TSHIRT -> { torso = rows(P); range(arm, 0, 3, P); leg = rows(T); range(leg, 10, 11, H); }
            case HOODIE -> { torso = rows(P); range(arm, 0, 10, P); leg = rows(T); range(leg, 10, 11, H); }
            case JACKET -> { torso = rows(S); range(arm, 0, 10, P); leg = rows(T); range(leg, 10, 11, H); }
            case SPORTS -> { torso = rows(P); range(arm, 0, 2, P); range(leg, 0, 5, S); range(leg, 7, 9, WHITE); range(leg, 10, 11, H); }
            case SCHOOL -> { torso = rows(WHITE); range(torso, 3, 11, P); range(arm, 0, 10, WHITE); leg = rows(T); range(leg, 10, 11, H); }
            case MEDIEVAL -> { torso = rows(P); range(arm, 0, 5, P); range(arm, 6, 9, LEATHER); leg = rows(T);
                range(leg, 8, 11, BOOT); range(leg, 8, 8, LEATHER); }
            case FANTASY -> { for (int r = 0; r < 12; r++) torso[r] = mix(P, S, r / 11.0);
                arm = rows(P); range(arm, 9, 11, S); leg = rows(S); leg[11] = GOLD; }
            case MODERN -> { torso = rows(WHITE); range(arm, 0, 9, P); arm[10] = WHITE; leg = rows(T); range(leg, 10, 11, H); }
        }
        solid(c, body, torso);
        solid(c, rArm, arm);
        solid(c, lArm, arm);
        solid(c, rLeg, leg);
        solid(c, lLeg, leg);

        switch (s.outfit()) {
            case TSHIRT -> {
                for (int x = 2; x <= 5; x++) fp(c, body, x, 0, skin);
                fp(c, body, 3, 1, skin);
                fp(c, body, 4, 1, skin);
                if (v % 3 == 0) { int row = 2 + (v / 3) % 6; ring(c, body, row, row + (v / 18) % 2, S, 3); }
                else if (v % 3 == 1) glyph(c, body, 1, 3, 3, 5, em, S, false);
            }
            case HOODIE -> {
                ring(c, jacket, 0, 1, shade(P, 12), 3);
                for (int x = 1; x <= 6; x++) for (int r = 2; r <= 4; r++) bp(c, jacket, x, r, shade(P, 8));
                for (int x = 1; x <= 6; x++) for (int r = 7; r <= 10; r++) fp(c, body, x, r, shade(P, -18));
                for (int r = 2; r <= 5; r++) { fp(c, body, 3, r, WHITE); fp(c, body, 4, r, WHITE); }
                glyph(c, body, 1, 5, 3, 5, em, shade(P, 40), true);
                ring(c, rArm, 10, 10, shade(P, -18), 2);
                ring(c, lArm, 10, 10, shade(P, -18), 2);
            }
            case JACKET -> {
                ring(c, jacket, 0, 11, P, 3);
                ring(c, jacket, 0, 0, shade(P, -18), 2);
                if (v % 2 == 0) c.clear(jacket.frontX() + 3, jacket.sideY() + 1, 2, 11);
                else for (int r = 1; r < 12; r++) fp(c, jacket, 3, r, shade(P, -35));
                glyph(c, jacket, 1, 3, 3, 5, em, S, true);
                ring(c, rArm, 10, 10, shade(P, -15), 2);
                ring(c, lArm, 10, 10, shade(P, -15), 2);
            }
            case SPORTS -> {
                for (int r = 0; r < 12; r++) {
                    fp(c, body, 0, r, S); fp(c, body, 7, r, S); bp(c, body, 0, r, S); bp(c, body, 7, r, S);
                }
                ring(c, body, 0, 0, S, 2);
                ring(c, rArm, 2, 2, S, 2);
                ring(c, lArm, 2, 2, S, 2);
                ring(c, rLeg, 0, 0, shade(S, -20), 2);
                ring(c, lLeg, 0, 0, shade(S, -20), 2);
                ring(c, rLeg, 8, 8, S, 2);
                ring(c, lLeg, 8, 8, S, 2);
                glyph(c, body, 2, 3, 2, 4, em, S, false);
                int num = v % 100, dc = lum(P) > 150 ? 0x202020 : WHITE;
                if (num >= 10) { digit(c, body, num / 10, 1, 3, dc); digit(c, body, num % 10, 4, 3, dc); }
                else digit(c, body, num, 3, 3, dc);
            }
            case SCHOOL -> {
                for (int r = 1; r <= 7; r++) { fp(c, body, 3, r, S); fp(c, body, 4, r, S); }
                fp(c, body, 3, 1, shade(S, -25));
                fp(c, body, 4, 1, shade(S, -25));
                glyph(c, body, 5, 4, 1, 3, em | 7, GOLD, false);
                ring(c, body, 11, 11, shade(P, -20), 2);
            }
            case MEDIEVAL -> {
                ring(c, body, 9, 10, BELT, 2);
                for (int x = 3; x <= 4; x++) for (int r = 9; r <= 10; r++) fp(c, body, x, r, GOLD);
                glyph(c, body, 1, 3, 3, 5, em, mix(GOLD, S, 0.25), false);
                ring(c, rPants, 0, 3, shade(P, -6), 3);
                ring(c, lPants, 0, 3, shade(P, -6), 3);
                ring(c, rPants, 3, 3, shade(P, -30), 2);
                ring(c, lPants, 3, 3, shade(P, -30), 2);
            }
            case FANTASY -> {
                for (int r = 0; r < 12; r++) { fp(c, body, 3, r, GOLD); fp(c, body, 4, r, GOLD); }
                ring(c, body, 11, 11, GOLD, 2);
                int rune = hsv((v % 360) / 360f, 0.6f, 1f);
                glyph(c, body, 2, 4, 2, 4, em, rune, false);
                ring(c, jacket, 0, 1, shade(S, 10), 3);
                ring(c, rSleeve, 0, 1, GOLD, 2);
                ring(c, lSleeve, 0, 1, GOLD, 2);
                for (int r = 0; r <= 9; r++) {
                    int col = mix(P, S, r / 9.0);
                    ring(c, rPants, r, r, col, 2);
                    ring(c, lPants, r, r, col, 2);
                }
            }
            case MODERN -> {
                for (int r = 1; r <= 6; r++) { fp(c, body, 3, r, S); fp(c, body, 4, r, S); }
                ring(c, jacket, 0, 9, P, 3);
                c.clear(jacket.frontX() + 3, jacket.sideY(), 2, 10);
                for (int r = 0; r <= 4; r++) { fp(c, jacket, 2, r, shade(P, -22)); fp(c, jacket, 5, r, shade(P, -22)); }
                ring(c, rLeg, 0, 0, shade(T, -30), 2);
                ring(c, lLeg, 0, 0, shade(T, -30), 2);
            }
        }

        paintHead(c, s, head, hat, jacket);
        return c.img;
    }

    private static void paintHead(Canvas c, Spec s, Box head, Box hat, Box jacket) {
        int skin = s.skin(), hairC = s.hairColor(), S = s.secondary(), v = s.variant();
        int fv = (v / 7) % 8; // face variant bits
        fillBox(c, head, skin, 3);
        int fx = head.frontX(), fy = head.sideY();
        int eye = s.eyes();
        boolean swap = (fv & 1) != 0;
        c.px(fx + 1, fy + 4, swap ? eye : WHITE);
        c.px(fx + 2, fy + 4, swap ? WHITE : eye);
        c.px(fx + 5, fy + 4, swap ? WHITE : eye);
        c.px(fx + 6, fy + 4, swap ? eye : WHITE);
        int brow = shade(hairC, -10);
        for (int x : new int[] {1, 2, 5, 6}) c.px(fx + x, fy + 3, brow);
        c.px(fx + 3, fy + 5, shade(skin, -14));
        c.px(fx + 4, fy + 5, shade(skin, -14));
        int mouth = mix(skin, 0xA04040, 0.55);
        c.px(fx + 3, fy + 6, mouth);
        c.px(fx + 4, fy + 6, mouth);
        if ((fv & 4) != 0) { c.px(fx + 2, fy + 6, shade(mouth, 10)); c.px(fx + 5, fy + 6, shade(mouth, 10)); }
        if ((fv & 2) != 0) for (int i = 0; i < 7; i++) {
            int x = c.rnd.nextInt(8);
            if (x != 3 && x != 4) c.px(fx + x, fy + 5 + c.rnd.nextInt(2), shade(skin, -20));
        }

        int fr, sb, sd, bd;
        switch (s.hair()) {
            case BUZZ -> { fr = 1; sb = 1; sd = 2; bd = 3; }
            case SHORT, SPIKY -> { fr = 2; sb = 2; sd = 4; bd = 6; }
            case LONG -> { fr = 2; sb = 8; sd = 8; bd = 8; }
            case BOB -> { fr = 2; sb = 6; sd = 6; bd = 7; }
            case MESSY -> { fr = 3; sb = 3; sd = 4; bd = 7; }
            default -> { fr = 1; sb = 2; sd = 3; bd = 5; }
        }
        c.fill(head.topX(), head.oy(), 8, 8, hairC, 5);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (y < (7 - x <= 1 ? sb : sd)) hairPx(c, head.rightX() + x, head.sideY() + y, hairC);
                if (y < (x <= 1 ? sb : sd)) hairPx(c, head.leftX() + x, head.sideY() + y, hairC);
                if (y < bd) hairPx(c, head.backX() + x, head.sideY() + y, hairC);
                boolean fringe = y < fr;
                if (fringe && y == fr - 1 && fr >= 2) {
                    fringe = s.hair() == Hair.MESSY ? c.rnd.nextInt(3) != 0 : (x * 5 + v) % 3 != 0;
                }
                if ((x <= 0 || x >= 7) && y < sb) fringe = true;
                if ((x == 1 || x == 6) && y < sb - 2) fringe = true;
                if (fringe) hairPx(c, fx + x, fy + y, hairC);
            }
        }
        if (s.accessory() == Accessory.BEARD) {
            for (int x = 1; x <= 6; x++) for (int y = 5; y <= 7; y++) {
                if (!(y == 6 && (x == 3 || x == 4))) hairPx(c, fx + x, fy + y, hairC);
            }
            for (int y = 5; y <= 6; y++) for (int x = 4; x < 8; x++) hairPx(c, head.rightX() + x, head.sideY() + y, hairC);
            for (int y = 5; y <= 6; y++) for (int x = 0; x < 4; x++) hairPx(c, head.leftX() + x, head.sideY() + y, hairC);
        }

        switch (s.hair()) {
            case LONG, BOB -> {
                int depth = s.hair() == Hair.LONG ? 8 : 6;
                for (int y = 0; y < depth; y++) for (int x = 0; x < 8; x++) {
                    if ((x + y) % 2 == 0 || y < 3) {
                        c.px(hat.rightX() + x, hat.sideY() + y, shade(hairC, 8));
                        c.px(hat.leftX() + x, hat.sideY() + y, shade(hairC, 8));
                        c.px(hat.backX() + x, hat.sideY() + y, shade(hairC, 8));
                    }
                }
            }
            case SPIKY -> {
                for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) if ((x + y) % 2 == 0) c.px(hat.topX() + x, hat.oy() + y, shade(hairC, 10));
                for (int x = 0; x < 8; x += 2) c.px(hat.frontX() + x, hat.sideY(), hairC);
            }
            case MESSY -> {
                for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) if (c.rnd.nextInt(5) < 2) c.px(hat.topX() + x, hat.oy() + y, shade(hairC, 14));
                for (int x = 0; x < 8; x++) if (c.rnd.nextInt(10) < 3) c.px(hat.frontX() + x, hat.sideY(), hairC);
            }
            case BEANIE -> {
                c.fill(hat.topX(), hat.oy(), 8, 8, S, 3);
                ring(c, hat, 0, 2, S, 3);
                ring(c, hat, 2, 2, shade(S, -25), 2);
                for (int x = 3; x <= 4; x++) for (int y = 3; y <= 4; y++) c.px(hat.topX() + x, hat.oy() + y, WHITE);
            }
            default -> { }
        }

        switch (s.accessory()) {
            case GLASSES -> {
                int frame = 0x1E1E1E;
                for (int x = 0; x < 8; x++) { c.px(hat.frontX() + x, hat.sideY() + 3, frame); c.px(hat.frontX() + x, hat.sideY() + 5, frame); }
                for (int x : new int[] {0, 3, 4, 7}) c.px(hat.frontX() + x, hat.sideY() + 4, frame);
                for (int x = 5; x < 8; x++) c.px(hat.rightX() + x, hat.sideY() + 4, frame);
                for (int x = 0; x < 3; x++) c.px(hat.leftX() + x, hat.sideY() + 4, frame);
            }
            case HEADBAND -> ring(c, hat, 2, 2, S, 2);
            case SCARF -> {
                ring(c, jacket, 0, 2, S, 3);
                for (int r = 3; r <= 7; r++) { fp(c, jacket, 5, r, shade(S, -12)); fp(c, jacket, 6, r, shade(S, -12)); }
            }
            default -> { }
        }
    }

    private static void hairPx(Canvas c, int x, int y, int hairC) {
        c.px(x, y, shade(hairC, c.rnd.nextInt(13) - 6));
    }
}
