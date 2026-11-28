package dev.nicks.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.nicks.core.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Skins for non-account nicknames must always be real, borrowed skins. */
class BorrowedSkinRegistryTest {
    private static class Env {
        final NicksConfig cfg = fastConfig();
        final Path dir = tempDir();
        final FakeMojang mojang = new FakeMojang(cfg);
        final List<String> accountNames = new ArrayList<>();
        NamePool pool;
        OfficialProfileResolver resolver;
        SkinTextureCache skins, capes;
        BorrowedSkinRegistry registry;

        Env(int accounts, boolean withCapes) {
            cfg.skin.backgroundDonorHarvest = false;
            cfg.skin.sources = new ArrayList<>(List.of("mojang-accounts"));
            for (int i = 0; i < accounts; i++) {
                String n = "realacct" + String.format("%03d", i);
                accountNames.add(n);
                mojang.add(n, i % 2 == 1, withCapes && i % 3 == 0 ? 0x2244CC : -1);
            }
            pool = pool(cfg, accountNames, Math.max(3 * accounts, 50));
            resolver = new OfficialProfileResolver(mojang, cfg);
            open();
        }

        void open() {
            skins = new SkinTextureCache(dir.resolve("skin_cache"), 64);
            capes = new SkinTextureCache(dir.resolve("cape_cache"), 64);
            registry = new BorrowedSkinRegistry(cfg, mojang, resolver, new SkinTextureDownloader(mojang, cfg), skins, capes, pool,
                    new AtomicJsonFile(dir.resolve("skin_donors.json")), new AtomicJsonFile(dir.resolve("borrowed_skins.json")),
                    dir.resolve("assets"));
        }

        void restart() {
            registry.close();
            open();
        }

        FakeMojang.Account accountOf(Appearance a) {
            return mojang.byLower.values().stream().filter(x -> x.id().equals(a.sourceUuid())).findFirst().orElseThrow();
        }
    }

    @Test void borrowedSkinIsARealAccountsSkinAndItsOwnCapeNeverAGeneratedOne() throws Exception {
        Env e = new Env(60, true);
        int caped = 0;
        for (int i = 0; i < 15; i++) {
            Appearance a = e.registry.appearanceFor("MadeUpName" + i, false);
            FakeMojang.Account acct = e.accountOf(a);
            assertEquals(acct.skinHash(), a.skin().hash(), "skin is exactly that account's skin");
            assertEquals("borrowed:account", a.sourceKind());
            if (acct.capeUrl() == null) {
                assertNull(a.cape(), "no cape on the donor = no cape");
                assertFalse(a.capeAvailable());
            } else {
                caped++;
                assertEquals(acct.capeHash(), a.cape().hash(), "cape comes from the same account as the skin");
                assertTrue(a.capeAvailable());
            }
            assertEquals(acct.slim() ? SkinModel.SLIM : SkinModel.CLASSIC, a.model());
        }
        assertTrue(caped > 0, "test data should include donors with capes");
    }

    @Test void aNicknameKeepsItsSkinForEveryPlayerAndAcrossRestarts() throws Exception {
        Env e = new Env(40, true);
        String h = e.registry.appearanceFor("Steve", false).skin().hash();
        assertEquals(h, e.registry.appearanceFor("steve", false).skin().hash(), "case-insensitive, shared by all players using it");
        assertEquals(h, e.registry.appearanceFor("STEVE", false).skin().hash());
        e.registry.flush();
        e.restart();
        assertEquals(h, e.registry.appearanceFor("Steve", false).skin().hash(), "same skin after a server restart");
    }

    @Test void differentNicknamesGetDifferentRealSkins() throws Exception {
        Env e = new Env(60, false);
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 25; i++) assertTrue(hashes.add(e.registry.appearanceFor("Nick" + i, false).skin().hash()));
    }

    @Test void withoutAnyReachableRealSourceTheRequestFailsInsteadOfMakingSomethingUp() {
        Env e = new Env(20, true);
        e.mojang.down = true;
        assertThrows(SkinException.class, () -> e.registry.appearanceFor("ToastBuilder", false));
        assertTrue(e.registry.assignments().isEmpty(), "nothing was stored");
    }

    @Test void aCapeThatCannotBeFetchedFailsTheWholeBorrowSoSkinAndCapeNeverGetSeparated() {
        Env e = new Env(30, false);
        for (String n : e.accountNames) e.mojang.add(n, false, 0x00AA00); // everybody has a cape now
        e.mojang.breakCapes = true;
        assertThrows(SkinException.class, () -> e.registry.appearanceFor("Someone", false));
        assertTrue(e.registry.assignments().isEmpty());
    }

    @Test void donorCapesCanBeSwitchedOff() throws Exception {
        Env e = new Env(30, true);
        e.cfg.skin.donorCapes = false;
        for (int i = 0; i < 10; i++) assertNull(e.registry.appearanceFor("N" + i, false).cape());
    }

    @Test void skinDatabaseIsUsedButOnlyMojangHostedTexturesAreAccepted() throws Exception {
        Env e = new Env(0, false);
        e.cfg.skin.sources = new ArrayList<>(List.of("skindb"));
        FakeMojang.Account classic = e.mojang.add("dbA", false, -1), slim = e.mojang.add("dbB", false, -1);
        e.mojang.skinDbJson = "[{\"id\":101,\"Skin\":{\"steveArms\":true},\"urls\":{\"mojang\":\"" + classic.skinUrl() + "\",\"clean\":\"https://assets.skindb.net/skins/101/skin.png\"}},"
                + "{\"id\":102,\"Skin\":{\"steveArms\":false},\"urls\":{\"mojang\":\"" + slim.skinUrl() + "\"}},"
                + "{\"id\":103,\"urls\":{\"mojang\":\"https://evil.example.com/texture/0123456789abcdef0123456789abcdef\"}}]";
        Appearance a = e.registry.appearanceFor("FirstNick", false), b = e.registry.appearanceFor("SecondNick", false);
        assertEquals(Set.of(classic.skinHash(), slim.skinHash()), Set.of(a.skin().hash(), b.skin().hash()));
        Appearance fromSlimEntry = a.skin().hash().equals(slim.skinHash()) ? a : b;
        assertEquals(SkinModel.SLIM, fromSlimEntry.model(), "steveArms=false means slim arms");
        assertEquals("borrowed:skindb", a.sourceKind());
        assertNull(a.cape());
    }

    @Test void adminSkinFilesAreAnotherRealSourceAndDuplicatesFollowTheConfig() throws Exception {
        Env e = new Env(0, false);
        e.cfg.skin.sources = new ArrayList<>(List.of("assets"));
        Files.createDirectories(e.dir.resolve("assets"));
        for (String f : new String[] {"hero.png", "mage.slim.png"}) {
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) img.setRGB(x, y, 0xFF000000 | (f.hashCode() & 0xFFFFFF));
            Files.write(e.dir.resolve("assets/" + f), SkinImages.encodePng(img));
        }
        Files.writeString(e.dir.resolve("assets/broken.png"), "garbage");
        e.restart();
        Appearance a = e.registry.appearanceFor("One", false), b = e.registry.appearanceFor("Two", false);
        assertNotEquals(a.skin().hash(), b.skin().hash());
        assertEquals(Set.of(SkinModel.CLASSIC, SkinModel.SLIM), Set.of(a.model(), b.model()));
        assertThrows(SkinException.class, () -> e.registry.appearanceFor("Three", false));
        e.cfg.skin.allowDuplicateBorrowedSkins = true;
        assertNotNull(e.registry.appearanceFor("Three", false));
    }

    @Test void rerollPicksADifferentRealSkinAndKeepsOneAssignment() throws Exception {
        Env e = new Env(40, false);
        String first = e.registry.appearanceFor("Reroll", false).skin().hash();
        String second = e.registry.appearanceFor("Reroll", true).skin().hash();
        assertNotEquals(first, second);
        assertEquals(1, e.registry.assignments().size());
        assertEquals(second, e.registry.appearanceFor("Reroll", false).skin().hash());
    }

    @Test void harvestingFindsRealAccountsInBatchesOfTenAndDoesNotDuplicate() {
        Env e = new Env(50, true);
        int added = e.registry.harvest(30, 1000, 10_000);
        assertTrue(added > 5, "found " + added);
        assertEquals(added, e.registry.accountDonorCount());
        assertTrue(e.mojang.bulkRequests.get() > 0);
        int again = e.registry.harvest(30, 1000, 10_000);
        assertEquals(added + again, e.registry.accountDonorCount(), "known accounts are not added twice");
        assertTrue(e.registry.accountDonorCount() <= 50);
    }

    @Test void donorStockSurvivesARestartSoNoNewProbingIsNeeded() throws Exception {
        Env e = new Env(50, false);
        e.registry.harvest(30, 1000, 10_000);
        int stock = e.registry.donorCount();
        e.registry.flush();
        e.restart();
        assertEquals(stock, e.registry.donorCount());
        int bulk = e.mojang.bulkRequests.get();
        e.registry.appearanceFor("AfterRestart", false);
        assertEquals(bulk, e.mojang.bulkRequests.get(), "served from the saved donor list");
    }
}
