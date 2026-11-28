# Nicks — nickname + live skin/cape identity replacement (Fabric, Minecraft 1.21.11, Java 21)

An admin runs `/nicks set Player123 ShadowWolf`. The tab list, nametag, chat, death messages, `/msg`, advancement and
join/leave messages now say **ShadowWolf**, and the player's actual skin **and cape** become ShadowWolf's if that Minecraft
account exists. No death, respawn, reconnect or restart is needed. The real UUID, account, inventory, permissions, stats and
player data are never touched.

## What changed in this version (your requests)

| Request | Now |
|---|---|
| No made-up skins | **Nothing is generated any more** (the procedural generator is deleted). A nickname that is not a real account borrows a **real, existing skin** from a real source (see "Skin sources"). |
| `/nicks set` must let many players share a nickname | `/nicks set @a Steve` gives every target that nickname (and the same appearance). `nickname.allowDuplicateNicknames` is `true` by default. Commands never guess between players who share a nickname. |
| Capes (spec Part 24) | Real accounts get skin **and** official cape from the same profile response, in a unified appearance record, with a separate cape cache and cape-aware commands. |
| Your 1,000,000-name file | Bundled in the jar, restored to `config/nicks/nickname-pools/` when missing, cached once, reloadable, used by `/nicks random`. My own name generator is deleted. |

## Read this first: what is and is not verified

| Part | Status |
|---|---|
| `dev.nicks.core`: identity manager, persistence, Mojang resolver (name, bulk, signed profile, skin, cape), skin/cape download + validation, borrowed-skin registry, name pool, texture ledger, change planner | **Implemented and tested.** 72 unit tests, run 5 times in a row, all passing (JDK 21, against a minimal JUnit stand-in because Gradle/JUnit could not be downloaded; they are standard JUnit 5 and will run under `gradle test`). The real 1,000,000-name file is loaded and checked line by line in a test. |
| `dev.nicks` Minecraft layer: commands, service, networking, mixins, client registry | **Implemented, compiled only against hand-written API stubs.** That proves my classes agree with each other, **not** that the real 1.21.11 signatures match. Never run in a game. |
| Live Mojang / SkinDB services | **Not reachable from where I worked.** Everything network-related is tested against scripted fakes that follow the documented API shapes. |
| Multiplayer behaviour (two clients, tracking range, spectator, dimension change...) | **Not tested.** |

Expect a few small compile errors on first build, confined to these spots (each marked `VERIFY ON 1.21.11` in the source):

| File | Why it may need a touch |
|---|---|
| `compat/SkinCompat.java` | `SkinTextures` constructor/accessors changed in 1.21.2, 1.21.5, 1.21.9; `DefaultSkinHelper.getSkinTextures(UUID)` |
| `compat/TextureCompat.java` | `NativeImageBackedTexture` constructor (looked up by signature, 3 variants tried) |
| `compat/PermissionCompat.java` | command permission API was reworked around 1.21.9–1.21.11 |
| `compat/ProfileCompat.java` | `GameProfile` `name()/id()` vs `getName()/getId()` |
| `mixin/PlayerEntityMixin`, `ServerPlayerEntityMixin`, `PlayerManagerMixin`, `client/PlayerListEntryMixin` | method names/descriptors of their targets |

Mixins are `require = 1` on purpose: a wrong target fails loudly at startup, never silently.

## Skin sources (no generated skins)

For a nickname that is a real account the skin and cape are that account's own. For a nickname Mojang confirms does **not**
exist, a real skin is borrowed from the first source in `skin.sources` that delivers:

1. **`mojang-accounts`** (official API, primary). The mod asks Mojang which of 10 random names from the username pool exist
   (`POST /minecraft/profile/lookup/bulk/byname`, 10 names per request, only existing accounts come back), then fetches each hit's
   signed profile. Those real accounts become "donors" (skin + that same account's cape), kept in `skin_donors.json`, topped up in
   the background (`skin.donorTarget`, default 300) so commands rarely wait. How many pool names are real accounts, I could not
   measure from here; if the hit rate is low the donor stock simply grows more slowly.
2. **`skindb`** (optional extra). A public skin database (api.skindb.net, `GET /skin/random?count=10`) whose entries carry a
   Mojang-hosted texture URL; only that URL is used and it must be on `textures.minecraft.net`. **Its documentation dates from 2019 and I
   could not confirm the service is still online**; if it is down the mod logs once at debug level and moves on (retry after 5 minutes).
3. **`assets`**: your own skin PNGs in `config/nicks/skin_assets/` (`name.png` classic, `name.slim.png` slim).

Rules: a nickname keeps its borrowed skin forever and every player using that nickname shares it; different nicknames get different
donors unless `skin.allowDuplicateBorrowedSkins`; if no real source can be reached the command fails, the previous identity stays and the
admin is told. I deliberately did not scrape NameMC or The Skindex (no public API, scraping is against their rules).

## Capes (spec Part 24)

* The signed `textures` property is fetched with `unsigned=false`; `SKIN` and `CAPE` are read from the same response. No `CAPE` entry =
  no cape: nothing is invented and the target's own cape is **not** kept (the client drops it while an identity is active).
* The cape PNG is validated (64x32, HD multiples, legacy 22x17/46x22) and never altered, so design, colours and transparency are exact.
  It is rendered through the normal player cape pipeline (and used for the elytra, as vanilla does).
* Skin and cape are one record (`Appearance`: source UUID, source name, skin, model, cape, cape availability, signature, update time).
  If the cape cannot be fetched the **whole change fails** and the old appearance stays (an outage is never read as "no cape").
* Separate caches: `skin_cache/` and `cape_cache/` (content-addressed, bounded, pruned). Clients register each texture once and free it
  when no identity uses it; the server tracks what each client holds, so A -> B -> A switches resend correctly (unit-tested).
* `nicknoskin` keeps skin and cape, `reset` restores the original skin and cape, `lock` protects both, `refresh` re-fetches both.

Cape scenarios from the spec: 1–9 are covered by automated tests (real account with/without cape, slim model, caped -> caped, caped -> uncaped,
fictional identity, `skin set`, lock, restart); **10 (two clients viewing the same caped player) needs a real game**.

## The one-million username pool

`nicks_1_million_usernames.txt` is bundled byte-for-byte (verified: exactly 1,000,000 lines, no duplicates even case-insensitively, all
3–16 characters of `A-Za-z0-9_`, no empty lines). Loaded once into a compact cache (measured: about 1 s and about 18 MB of heap, instead of a million String objects), original order preserved, never re-read per command.

* Location `config/nicks/nickname-pools/nicks_1_million_usernames.txt`; a missing default file is restored from the jar.
* `/nicks reload` re-reads it. A corrupted file (empty, or mostly invalid lines) is **rejected**, the previous pool stays active and the problem is reported.
  Only if there never was a valid pool is the full bundled copy used (loudly). There is no tiny built-in list.
* `/nicks random` picks random entries, never one that is online/in use, never a recently used one (`nickname.recentRandomMemory`), then checks it with Mojang:
  real account -> its skin and cape, otherwise a borrowed real skin. The names are candidates, not proof of availability.
* A small offensive-fragment filter (`nickname.bannedFragments`) skips about 375 of the million entries, some only because of innocent substrings (for example "vete**rape**arl").

## Install / build
Server **and** every client need the mod (Fabric API required). Clients without it still see all name changes but not custom skins, capes or nametags.

1. Copy `minecraft_version / yarn_mappings / loader_version / loom_version / fabric_version` from the `gradle.properties` of a 1.21.11 project that already builds for you (mine are guesses).
2. Use the same Gradle version as that project (`.github/workflows/build.yml` uses 9.8.0 like your other Loom 1.19 builds, `gradle build --no-daemon`, no wrapper).
3. Jar: `build/libs/nicks-1.0.0.jar`.

## Commands (admin; gamemaster level). Targets: `@a @p @s @r`, player names, nicknames.
```
/nicks random <targets>                  distinct random names from the pool, skin + cape from the real account or a borrowed real skin
/nicks set <targets> <nickname>          every target gets this nickname (and the same appearance)
/nicks skin set <targets> [source]       skin AND cape of a real account; no source = appearance for the current name
/nicks nicknoskin set <targets> <nick>   nickname only; skin AND cape untouched, no lookups
/nicks reset <targets>                   original name, skin and cape back (history kept)
/nicks info <targets>   /nicks history <targets>
/nicks skin refresh <targets>            re-fetch skin and cape / re-sync
/nicks skin reroll <targets> [force]     different borrowed real skin for this nickname
/nicks skin lock|unlock <targets>        locked: nickname commands never change skin or cape
/nicks reload                            config, skin assets and the username pool (invalid files keep the old state)
```

## How it works
| Feature | Mechanism |
|---|---|
| Chat, death/advancement/join/leave messages, `/msg` | One mixin on `PlayerEntity#getName` (all are built from `getDisplayName()`); signed-chat data is untouched |
| Tab list | `ServerPlayerEntity#getPlayerListName` + vanilla `UPDATE_DISPLAY_NAME`; no fake entries |
| Nametag | The same `getName` hook on the client, so the real nametag renderer shows it |
| Skin + cape | Client mixin on `PlayerListEntry#getSkinTextures`, read every frame: changes appear on the next frame |
| Commands | `PlayerManager#getPlayer(String)` falls back to the nickname registry; ambiguous nicknames never resolve |
| Sync | S2C `nicks:identity_sync` and `nicks:skin_texture`; server-authoritative, revisioned, no C2S payload |
| Async | Lookups, downloads and file IO run off the tick; newer commands make older results stale |

## Files
`config/nicks/config.json` (rewritten with all options on load), `nickname-pools/`, `skin_assets/`. Per world in `<world>/nicks/`:
`identities.json`, `borrowed_skins.json`, `skin_donors.json`, `skin_cache/`, `cape_cache/` (atomic writes, `.bak`, corrupt files quarantined, newer schemas read-only).

## Public API (`dev.nicks.api`)
`NicksApi`: read identity/nickname/display name/source/type; `setNickname / setSkin / resetIdentity` (same validation, locks and sync as the commands); `registerSkinProvider`. `NicksEvents`: before/after nickname change, skin change, identity reset (before-events can veto).

## Known limitations
* Vanilla clients cannot see custom skins, capes or the replaced nametag.
* `@a[name=Player123]` now matches the nickname; the real name still works as a literal player name. With shared nicknames, `/tp Steve` resolves to the real player named Steve if one is online, otherwise it is ambiguous and not resolved.
* The server log shows nicknames in some lines (`X lost connection`).
* Third-party commands with their own player argument type are not covered unless they use `PlayerManager#getPlayer(String)`.
* A borrowed skin is the public skin of a real player, shown under a different name (that is what "borrowed from a real source" means).

## Not implemented
* Scoreboard display replacement, nickname-based tab sorting, boss bars (no player names there).
* Offline-mode servers and proxies were not tested.

## In-game test plan (not run yet)
1. Two clients A, B. `/nicks set A Notch`: both see the skin and cape, name in tab/nametag/chat. A slim account: slim arms. An account without a cape: no cape (A's own cape disappears).
2. `/nicks set @a Steve`: every target is Steve with the same look. `/nicks set A ToastBuilder`: a real borrowed skin; restart: unchanged.
3. `/nicks nicknoskin set A Foo` keeps skin and cape; `/nicks skin set A Notch` keeps the name; switch A -> B -> A repeatedly; lock, then `/nicks set`.
4. `/tp Foo B`, `/gamemode creative Foo`; deaths, `/msg`; `/nicks reset A` (original skin and cape return).
5. Late joiner, tracking range, dimension change, spectating A; two clients watching the same caped player.
6. Block `api.minecraftservices.com`: `/nicks set A Notch` fails in red and A is unchanged.
7. Rename or corrupt the pool file, `/nicks reload`: error shown, old pool kept; delete it, restart: restored from the jar.
