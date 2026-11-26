package dev.nicks.core;

/** Player model arm geometry. */
public enum SkinModel {
    CLASSIC, SLIM;

    public static SkinModel parse(String s) {
        return s != null && (s.equalsIgnoreCase("slim") || s.equalsIgnoreCase("SLIM")) ? SLIM : CLASSIC;
    }

    public boolean slim() { return this == SLIM; }
}
