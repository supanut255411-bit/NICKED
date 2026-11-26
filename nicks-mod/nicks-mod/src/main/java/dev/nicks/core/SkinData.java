package dev.nicks.core;

import java.util.Objects;

/** An immutable, validated, normalized 64x64 skin: content hash, arm model and PNG bytes. */
public record SkinData(String hash, SkinModel model, byte[] png) {
    public SkinData {
        Objects.requireNonNull(hash);
        Objects.requireNonNull(model);
        Objects.requireNonNull(png);
    }

    @Override public boolean equals(Object o) {
        return o instanceof SkinData d && d.hash.equals(hash) && d.model == model;
    }

    @Override public int hashCode() { return Objects.hash(hash, model); }

    @Override public String toString() { return "SkinData[" + hash.substring(0, 8) + ", " + model + ", " + png.length + "B]"; }
}
