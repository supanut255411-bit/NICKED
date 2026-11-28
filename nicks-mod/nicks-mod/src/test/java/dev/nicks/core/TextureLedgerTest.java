package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TextureLedgerTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64), CAPE1 = "c".repeat(64), CAPE2 = "d".repeat(64);

    @Test void switchingAToBAndBackResendsTheTexture() {
        TextureLedger l = new TextureLedger();
        UUID viewer = UUID.randomUUID(), player = UUID.randomUUID();
        assertTrue(l.skinNeeded(viewer, A));
        l.record(viewer, player, A, "");
        assertFalse(l.skinNeeded(viewer, A), "client already holds A");
        assertTrue(l.skinNeeded(viewer, B));
        l.record(viewer, player, B, ""); // client frees A now: nobody references it
        assertTrue(l.skinNeeded(viewer, A), "A must be sent again when the player switches back");
    }

    @Test void aTextureStaysWhileAnyPlayerOnThatClientStillUsesIt() {
        TextureLedger l = new TextureLedger();
        UUID viewer = UUID.randomUUID(), p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        l.record(viewer, p1, A, CAPE1);
        l.record(viewer, p2, A, CAPE1);
        l.record(viewer, p1, B, "");
        assertFalse(l.skinNeeded(viewer, A));
        assertFalse(l.capeNeeded(viewer, CAPE1));
        l.record(viewer, p2, B, "");
        assertTrue(l.skinNeeded(viewer, A));
        assertTrue(l.capeNeeded(viewer, CAPE1));
    }

    @Test void capeAndSkinAreTrackedIndependentlyAndTwoViewersDoNotInterfere() {
        TextureLedger l = new TextureLedger();
        UUID v1 = UUID.randomUUID(), v2 = UUID.randomUUID(), player = UUID.randomUUID();
        l.record(v1, player, A, CAPE1);
        assertTrue(l.skinNeeded(v2, A), "viewer 2 has received nothing yet");
        l.record(v1, player, A, CAPE2); // new cape, same skin
        assertFalse(l.skinNeeded(v1, A));
        assertTrue(l.capeNeeded(v1, CAPE1));
        assertFalse(l.capeNeeded(v1, CAPE2));
        l.record(v1, player, A, ""); // caped -> no cape
        assertTrue(l.capeNeeded(v1, CAPE2));
    }

    @Test void forgetPlayerAndInvalidate() {
        TextureLedger l = new TextureLedger();
        UUID v = UUID.randomUUID(), p = UUID.randomUUID();
        l.record(v, p, A, CAPE1);
        l.invalidate(A, "");
        assertTrue(l.skinNeeded(v, A));
        assertFalse(l.capeNeeded(v, CAPE1));
        l.forgetPlayer(p);
        assertTrue(l.capeNeeded(v, CAPE1));
        l.record(v, p, B, "");
        l.forgetViewer(v);
        assertTrue(l.skinNeeded(v, B));
    }
}
