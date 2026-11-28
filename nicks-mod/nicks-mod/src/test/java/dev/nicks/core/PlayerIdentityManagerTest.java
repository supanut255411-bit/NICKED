package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerIdentityManagerTest {
    private static PlayerIdentityManager manager(NicksConfig cfg, Path dir) {
        return new PlayerIdentityManager(cfg, new AtomicJsonFile(dir.resolve("identities.json")));
    }

    private static void nick(PlayerIdentityManager m, UUID u, String nick) {
        var r = m.apply(u, -1, nick, b -> { b.nickname = nick; b.active = true; b.identityType = IdentityType.FICTIONAL; b.skinHash = "a".repeat(64); });
        assertEquals(PlayerIdentityManager.Status.APPLIED, r.status());
    }

    @Test void applyBumpsRevisionAndKeepsUuidAndOriginalName() {
        PlayerIdentityManager m = manager(fastConfig(), tempDir());
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "Player123");
        nick(m, u, "ShadowWolf");
        PlayerIdentity id = m.get(u).orElseThrow();
        assertEquals(u, id.playerUuid());
        assertEquals("Player123", id.originalUsername());
        assertEquals("ShadowWolf", id.effectiveName());
        assertEquals(1, id.revision());
        nick(m, u, "OtherName");
        assertEquals(2, m.get(u).orElseThrow().revision());
        assertEquals(java.util.List.of("ShadowWolf"), m.get(u).orElseThrow().nicknameHistory());
        m.close();
    }

    @Test void staleAsyncResultsNeverOverwriteNewerChanges() {
        PlayerIdentityManager m = manager(fastConfig(), tempDir());
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "P");
        long first = m.nextGeneration(u);
        long second = m.nextGeneration(u); // rapid second command
        var late = m.apply(u, first, "Old", b -> { b.nickname = "Old"; b.active = true; });
        assertEquals(PlayerIdentityManager.Status.STALE, late.status());
        var ok = m.apply(u, second, "New", b -> { b.nickname = "New"; b.active = true; });
        assertEquals(PlayerIdentityManager.Status.APPLIED, ok.status());
        assertEquals("New", m.get(u).orElseThrow().nickname());
        m.close();
    }

    @Test void duplicateNicknamesAndForeignAccountNamesAreRejectedWhenDuplicatesAreSwitchedOff() {
        NicksConfig cfg = fastConfig();
        cfg.nickname.allowDuplicateNicknames = false;
        PlayerIdentityManager m = manager(cfg, tempDir());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        m.getOrCreate(a, "Alice");
        m.getOrCreate(b, "Bob");
        nick(m, a, "ShadowWolf");
        assertEquals(PlayerIdentityManager.Status.CONFLICT, m.apply(b, -1, "shadowwolf", x -> { x.nickname = "shadowwolf"; x.active = true; }).status());
        assertEquals(PlayerIdentityManager.Status.CONFLICT, m.apply(b, -1, "alice", x -> { x.nickname = "alice"; x.active = true; }).status());
        assertNull(m.nicknameConflict(a, "Alice"), "a player may use their own account name");
        m.close();
    }

    @Test void nicknameResolutionIsCaseInsensitiveAndFlagsAmbiguity() {
        NicksConfig cfg = fastConfig();
        cfg.nickname.allowDuplicateNicknames = true;
        PlayerIdentityManager m = manager(cfg, tempDir());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        m.getOrCreate(a, "A1");
        m.getOrCreate(b, "B1");
        nick(m, a, "Twin");
        assertEquals(a, m.resolveNickname("tWiN").orElseThrow().uuid());
        assertFalse(m.resolveNickname("Twin").orElseThrow().ambiguous());
        nick(m, b, "Twin");
        assertTrue(m.resolveNickname("Twin").orElseThrow().ambiguous(), "never silently pick one of two holders");
        m.close();
    }

    @Test void resetClearsDisplayedLayerButKeepsHistoryAndLock() {
        PlayerIdentityManager m = manager(fastConfig(), tempDir());
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "Real");
        nick(m, u, "Fake");
        m.apply(u, -1, null, b -> b.skinLocked = true);
        m.apply(u, -1, null, b -> b.clearDisplayed());
        PlayerIdentity id = m.get(u).orElseThrow();
        assertFalse(id.active());
        assertEquals("Real", id.effectiveName());
        assertTrue(id.skinLocked());
        assertEquals(java.util.List.of("Fake"), id.nicknameHistory());
        assertTrue(m.resolveNickname("Fake").isEmpty());
        m.close();
    }

    @Test void historyIsBounded() {
        NicksConfig cfg = fastConfig();
        cfg.nickname.historyLimit = 3;
        PlayerIdentityManager m = manager(cfg, tempDir());
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "P");
        for (int i = 0; i < 10; i++) nick(m, u, "Name" + i);
        assertEquals(3, m.get(u).orElseThrow().nicknameHistory().size());
        m.close();
    }

    @Test void identitiesSurviveRestartAndKeepRevision() throws Exception {
        Path dir = tempDir();
        PlayerIdentityManager m = manager(fastConfig(), dir);
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "Player123");
        nick(m, u, "ShadowWolf");
        m.close();
        PlayerIdentityManager again = manager(fastConfig(), dir);
        PlayerIdentity id = again.get(u).orElseThrow();
        assertEquals("ShadowWolf", id.nickname());
        assertEquals(1, id.revision());
        assertEquals(u, again.resolveNickname("shadowwolf").orElseThrow().uuid());
        again.close();
    }

    @Test void corruptedFileIsQuarantinedAndBackupRecovered() throws Exception {
        Path dir = tempDir();
        PlayerIdentityManager m = manager(fastConfig(), dir);
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "P");
        nick(m, u, "One");
        m.flush();
        nick(m, u, "Two");
        m.flush(); // now identities.json = Two, identities.json.bak = One
        m.close();
        Files.writeString(dir.resolve("identities.json"), "{ this is not json");
        PlayerIdentityManager again = manager(fastConfig(), dir);
        assertTrue(again.get(u).isPresent(), "identity recovered from backup");
        assertEquals("Two", again.get(u).orElseThrow().nickname());
        try (var s = Files.list(dir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")), "bad file kept for inspection");
        }
        again.close();
    }

    @Test void newerSchemaIsLoadedReadOnlyAndNeverOverwritten() throws Exception {
        Path dir = tempDir();
        String future = "{\"schema\": 99, \"identities\": [], \"extra\": \"from the future\"}";
        Files.writeString(dir.resolve("identities.json"), future);
        PlayerIdentityManager m = manager(fastConfig(), dir);
        m.getOrCreate(UUID.randomUUID(), "X");
        m.close();
        assertEquals(future, Files.readString(dir.resolve("identities.json")));
    }

    @Test void usernameChangeOfRealAccountIsTracked() {
        NicksConfig cfg = fastConfig();
        cfg.nickname.allowDuplicateNicknames = false;
        PlayerIdentityManager m = manager(cfg, tempDir());
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "OldName");
        m.getOrCreate(u, "NewName");
        assertEquals("NewName", m.get(u).orElseThrow().originalUsername());
        assertNull(m.nicknameConflict(UUID.randomUUID(), "OldName"));
        assertNotNull(m.nicknameConflict(UUID.randomUUID(), "NewName"));
        m.close();
    }

    @Test void byDefaultManyPlayersCanShareOneNickname() {
        PlayerIdentityManager m = manager(fastConfig(), tempDir());
        java.util.List<UUID> players = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) { UUID u = UUID.randomUUID(); players.add(u); m.getOrCreate(u, "Player" + i); }
        for (UUID u : players) nick(m, u, "Steve"); // /nicks set @a Steve
        for (UUID u : players) assertEquals("Steve", m.get(u).orElseThrow().nickname());
        assertTrue(m.resolveNickname("steve").orElseThrow().ambiguous(), "commands never guess between players sharing a nickname");
        // even a nickname equal to somebody else's real account name is accepted by default
        UUID other = UUID.randomUUID();
        m.getOrCreate(other, "Notch");
        nick(m, players.get(0), "Notch");
        m.close();
    }

    @Test void appearanceWithCapeSurvivesARestartAndIsClearedByReset() throws Exception {
        Path dir = tempDir();
        PlayerIdentityManager m = manager(fastConfig(), dir);
        UUID u = UUID.randomUUID(), src = UUID.randomUUID();
        m.getOrCreate(u, "Player123");
        SkinData skin = new SkinData("a".repeat(64), SkinModel.SLIM, new byte[] {1});
        SkinData cape = new SkinData("b".repeat(64), SkinModel.CLASSIC, new byte[] {2});
        Appearance a = new Appearance(src, "Notch", "official", skin, SkinModel.SLIM, cape, true, "SIG", 12345L);
        m.apply(u, -1, "Notch", b -> { b.nickname = "Notch"; b.active = true; b.setAppearance(IdentityType.REAL_ACCOUNT, "official:Notch", a); });
        m.close();
        PlayerIdentityManager again = manager(fastConfig(), dir);
        PlayerIdentity id = again.get(u).orElseThrow();
        assertEquals("b".repeat(64), id.capeHash());
        assertTrue(id.capeAvailable());
        assertEquals(src, id.officialProfileUuid());
        assertEquals("Notch", id.sourceName());
        assertEquals("SIG", id.textureSignature());
        assertEquals(12345L, id.appearanceUpdated());
        assertEquals(SkinModel.SLIM, id.skinModel());
        assertTrue(id.hasAppearance());
        again.apply(u, -1, null, PlayerIdentity.Builder::clearDisplayed);
        PlayerIdentity reset = again.get(u).orElseThrow();
        assertEquals("", reset.capeHash());
        assertFalse(reset.capeAvailable());
        assertFalse(reset.hasAppearance(), "original skin and cape show again");
        again.close();
    }
}
