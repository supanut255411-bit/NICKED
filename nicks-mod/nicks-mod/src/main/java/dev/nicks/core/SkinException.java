package dev.nicks.core;

/** A skin could not be downloaded, validated or generated. */
public class SkinException extends Exception {
    private static final long serialVersionUID = 1L;

    public SkinException(String message) { super(message); }
}
