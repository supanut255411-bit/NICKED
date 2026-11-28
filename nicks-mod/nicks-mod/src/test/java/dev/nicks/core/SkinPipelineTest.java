package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Nickname in, ready-to-apply skin + cape out, with a scripted Mojang (spec Part 24 scenarios). */
class SkinPipelineTest {
    private static class Env {
        final NicksConfig cfg = fastConfig();
        final Path dir = tempDir();
        final FakeMojang mojang = new FakeMojang(cfg);
        final List<String> donorNames = new ArrayList<>();
        SkinTextureCache skins, capes;
        OfficialProfileResolver resolver;
        BorrowedSkinRegistry registry;
        SkinPipeline pipeline;

        Env() {
            cfg.skin.backgroundDonorHarvest = false;
            cfg.skin.sources = new ArrayList<>(List.of("mojang-accounts"));
            mojang.add("Notch", false, 0xAA2222);   // classic arms, cape
            mojang.add("Dream", true, -1);          // slim arms, no cape
            mojang.add("Jeb_", false, 0x2266DD);    // classic, a different cape
            mojang.addDefaultSkin("PlainAccount");  // never set a skin
            for (int i = 0; i < 30; i++) { String n = "donor" + String.format("%02d", i); donorNames.add(n); mojang.add(n, i % 2 == 0, i % 4 == 0 ? 0x888888 : -1); }
            open();
        }

        void open() {
            skins = new SkinTextureCache(dir.resolve("skin_cache"), 64);
            capes = new SkinTextureCache(dir.resolve("cape_cache"), 64);
            resolver = new OfficialProfileResolver(mojang, cfg);
            SkinTextureDownloader dl = new SkinTextureDownloader(mojang, cfg);
            registry = new BorrowedSkinRegistry(cfg, mojang, resolver, dl, skins, capes, pool(cfg, donorNames, 60),
                    new AtomicJsonFile(dir.resolve("skin_donors.json")), new AtomicJsonFile(dir.resolve("borrowed_skins.json")), dir.resolve("assets"));
            pipeline = new SkinPipeline(cfg, resolver, dl, skins, capes, registry);
        }

        SkinPipeline.Resolved resolve(String nick) { return (SkinPipeline.Resolved) pipeline.forNickname(UUID.randomUUID(), nick, false); }
    }

    private static PlayerIdentityManager manager(Path dir) {
        return new PlayerIdentityManager(fastConfig(), new AtomicJsonFile(dir.resolve("identities.json")));
    }

    private static void applyAppearance(PlayerIdentityManager m, UUID u, String nick, SkinPipeline.Resolved r) {
        m.apply(u, -1, nick, b -> { if (nick != null) b.nickname = nick; b.active = true; b.setAppearance(r.type(), r.skinSource(), r.appearance()); });
    }

    // 1. real account with an official cape
    @Test void realAccountWithCapeGetsSkinAndTheExactCape() {
        Env e = new Env();
        SkinPipeline.Resolved r = e.resolve("Notch");
        FakeMojang.Account notch = e.mojang.byLower.get("notch");
        assertEquals(IdentityType.REAL_ACCOUNT, r.type());
        assertEquals(notch.skinHash(), r.appearance().skin().hash());
        assertEquals(notch.capeHash(), r.appearance().cape().hash(), "the actual cape texture, not an imitation");
        assertTrue(r.appearance().capeAvailable());
        assertEquals("SIGNATURE", r.appearance().signature());
        assertEquals(notch.id(), r.appearance().sourceUuid());
        assertEquals("Notch", r.appearance().sourceName());
        assertTrue(e.capes.get(notch.capeHash(), SkinModel.CLASSIC).isPresent(), "stored in the separate cape cache");
        assertFalse(e.skins.get(notch.capeHash(), SkinModel.CLASSIC).isPresent(), "capes are not mixed into the skin cache");
    }

    // 2 + 3. no cape / different model
    @Test void accountWithoutCapeHasNoCapeAndModelFollowsTheProfile() {
        Env e = new Env();
        SkinPipeline.Resolved dream = e.resolve("Dream");
        assertNull(dream.appearance().cape(), "no invented cape");
        assertFalse(dream.appearance().capeAvailable());
        assertEquals(SkinModel.SLIM, dream.appearance().model());
        assertEquals(SkinModel.CLASSIC, e.resolve("Notch").appearance().model());
    }

    @Test void accountWithoutACustomSkinUsesItsDefaultSkinMarker() {
        Env e = new Env();
        SkinPipeline.Resolved r = e.resolve("PlainAccount");
        assertEquals(IdentityType.REAL_ACCOUNT, r.type());
        assertTrue(r.appearance().usesDefaultSkin());
        assertEquals(e.mojang.byLower.get("plainaccount").id(), r.appearance().sourceUuid(), "default skin is chosen by the source account's UUID");
    }

    // 4. caped -> caped, 5. caped -> no cape
    @Test void changingBetweenAccountsReplacesTheCapeAndNeverKeepsTheOldOne() {
        Env e = new Env();
        PlayerIdentityManager m = manager(e.dir);
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "Player123");
        applyAppearance(m, u, "Notch", e.resolve("Notch"));
        String notchCape = m.get(u).orElseThrow().capeHash();
        applyAppearance(m, u, "Jeb_", e.resolve("Jeb_"));
        PlayerIdentity jeb = m.get(u).orElseThrow();
        assertEquals(e.mojang.byLower.get("jeb_").capeHash(), jeb.capeHash());
        assertNotEquals(notchCape, jeb.capeHash());
        assertEquals(e.mojang.byLower.get("jeb_").skinHash(), jeb.skinHash(), "skin and cape both from Jeb_");
        applyAppearance(m, u, "Dream", e.resolve("Dream"));
        PlayerIdentity dream = m.get(u).orElseThrow();
        assertEquals("", dream.capeHash(), "caped -> uncaped removes the cape");
        assertFalse(dream.capeAvailable());
        assertEquals(e.mojang.byLower.get("dream").skinHash(), dream.skinHash());
        m.close();
    }

    // 6. fictional identity: a borrowed REAL skin
    @Test void nicknameThatIsNotAnAccountBorrowsARealSkin() {
        Env e = new Env();
        SkinPipeline.Resolved r = e.resolve("ToastBuilder");
        assertEquals(IdentityType.FICTIONAL, r.type());
        assertTrue(r.skinSource().startsWith("borrowed:"));
        Set<String> real = new java.util.HashSet<>();
        e.mojang.byLower.values().forEach(a -> real.add(a.skinHash()));
        assertTrue(real.contains(r.appearance().skin().hash()), "pixel-for-pixel a real account's skin");
    }

    // 7. /nicks skin set <source> copies skin AND cape
    @Test void skinSetCopiesBothFromTheSourceAndRejectsUnknownAccounts() {
        Env e = new Env();
        SkinPipeline.Resolved r = (SkinPipeline.Resolved) e.pipeline.forSkinSource("Notch", false);
        assertEquals(e.mojang.byLower.get("notch").skinHash(), r.appearance().skin().hash());
        assertEquals(e.mojang.byLower.get("notch").capeHash(), r.appearance().cape().hash());
        assertTrue(e.pipeline.forSkinSource("DefinitelyNotReal", false) instanceof SkinPipeline.Failed);
    }

    // 8. locked during a nickname change: nickname-only change leaves skin AND cape alone
    @Test void nicknameOnlyChangeKeepsSkinAndCapeUntouched() {
        Env e = new Env();
        PlayerIdentityManager m = manager(e.dir);
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "Player123");
        applyAppearance(m, u, "Notch", e.resolve("Notch"));
        m.apply(u, -1, "TestName", b -> { b.nickname = "TestName"; b.active = true; }); // /nicks nicknoskin
        PlayerIdentity id = m.get(u).orElseThrow();
        assertEquals("TestName", id.nickname());
        assertEquals(e.mojang.byLower.get("notch").skinHash(), id.skinHash());
        assertEquals(e.mojang.byLower.get("notch").capeHash(), id.capeHash());
        m.close();
    }

    // 9. server restart with an existing cape assignment
    @Test void capeAssignmentSurvivesARestartIncludingTheTexturePixels() {
        Env e = new Env();
        PlayerIdentityManager m = manager(e.dir);
        UUID u = UUID.randomUUID();
        m.getOrCreate(u, "Player123");
        applyAppearance(m, u, "Notch", e.resolve("Notch"));
        m.close();
        e.open(); // new caches over the same disk, as after a restart
        PlayerIdentityManager again = manager(e.dir);
        PlayerIdentity id = again.get(u).orElseThrow();
        SkinPipeline.Textures t = e.pipeline.textures(id);
        assertEquals(e.mojang.byLower.get("notch").skinHash(), t.skin().hash());
        assertEquals(e.mojang.byLower.get("notch").capeHash(), t.cape().hash());
        again.close();
    }

    @Test void capeDownloadFailureFailsTheWholeChangeSoThePreviousLookStays() {
        Env e = new Env();
        e.mojang.breakCapes = true;
        assertTrue(e.pipeline.forNickname(UUID.randomUUID(), "Notch", false) instanceof SkinPipeline.Failed);
        assertTrue(e.resolve("Dream") != null, "an account without a cape is unaffected");
    }

    @Test void mojangOutageNeverTurnsARealAccountIntoAMadeUpOne() {
        Env e = new Env();
        e.mojang.down = true;
        SkinPipeline.Failed f = (SkinPipeline.Failed) e.pipeline.forNickname(UUID.randomUUID(), "Notch", false);
        assertTrue(f.transientError());
        assertTrue(e.registry.assignments().isEmpty(), "no skin was borrowed during the outage");
    }

    @Test void everyoneGettingTheSameNicknameShareOneLookupAndOneAppearance() throws Exception {
        Env e = new Env();
        List<CompletableFuture<SkinPipeline.Outcome>> real = new ArrayList<>(), fake = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID p = UUID.randomUUID();
            real.add(CompletableFuture.supplyAsync(() -> e.pipeline.forNickname(p, "Notch", false)));
            fake.add(CompletableFuture.supplyAsync(() -> e.pipeline.forNickname(p, "ShadowWolf99", false)));
        }
        Set<String> realHashes = new java.util.HashSet<>(), fakeHashes = new java.util.HashSet<>();
        for (var f : real) realHashes.add(((SkinPipeline.Resolved) f.get(30, TimeUnit.SECONDS)).appearance().skin().hash());
        for (var f : fake) fakeHashes.add(((SkinPipeline.Resolved) f.get(30, TimeUnit.SECONDS)).appearance().skin().hash());
        assertEquals(1, realHashes.size());
        assertEquals(1, fakeHashes.size(), "/nicks set @a ShadowWolf99: all players look the same");
        assertEquals(1, e.registry.assignments().size());
    }

    @Test void freeFormNamesAndDisabledOfficialLookupBorrowWithoutAskingMojangAboutTheName() {
        Env e = new Env();
        int before = e.mojang.requests.get();
        assertEquals(IdentityType.FICTIONAL, e.resolve("Shadow Wolf").type());
        e.cfg.skin.enableOfficialResolution = false;
        assertEquals(IdentityType.FICTIONAL, e.resolve("Notch").type());
        assertTrue(e.mojang.requests.get() > before, "only donor discovery, never a lookup of the nickname itself");
    }
}
