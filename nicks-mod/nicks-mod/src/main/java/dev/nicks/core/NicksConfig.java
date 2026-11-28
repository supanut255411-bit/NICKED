package dev.nicks.core;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Documented configuration (config/nicks/config.json). Every public field is a JSON key; unknown keys are
 * ignored and missing keys fall back to the defaults below. The file is rewritten on load so new options
 * appear automatically after an update.
 */
public final class NicksConfig {
    public static final int SCHEMA = 1;

    public boolean debug = false;
    public final Nickname nickname = new Nickname();
    public final Skin skin = new Skin();
    public final Display display = new Display();
    public final Commands commands = new Commands();
    public final Network network = new Network();
    public final Storage storage = new Storage();
    /** Message overrides, e.g. {"set.success": "Changed {0} to {1}"}. See {@code Messages} for the keys. */
    public Map<String, String> messages = new LinkedHashMap<>();

    public static final class Nickname {
        public boolean enableRandom = true;
        public int randomMinLength = 3;
        public int randomMaxLength = 16;
        /** Several players may share one nickname (needed for /nicks set @a Steve). Set false to refuse duplicates. */
        public boolean allowDuplicateNicknames = true;
        public boolean preserveHistory = true;
        public int historyLimit = 50;
        /** Keep resolving the real username to the player in commands. */
        public boolean allowOriginalUsernameAlias = true;
        /** If a nickname and a real username collide in a command, prefer the nickname. */
        public boolean nicknameFirstResolution = false;
        /** Accept nicknames that are not valid account names (spaces etc.); they are always fictional. */
        public boolean allowFreeFormNicknames = false;
        /** Optional vanilla colour name for nicknames ("gold", "aqua", ...). Empty = keep team colour only. */
        public String color = "";
        public int recentRandomMemory = 200;
        /** File inside config/nicks/nickname-pools/ used by /nicks random (default copy is bundled in the jar). */
        public String poolFile = "nicks_1_million_usernames.txt";
        public List<String> bannedFragments = new ArrayList<>(List.of(
                "fuck", "shit", "bitch", "cunt", "nigg", "fagg", "rape", "nazi", "hitler", "porn", "kkk"));
    }

    public static final class Skin {
        public boolean enableOfficialResolution = true;
        /**
         * Where skins for nicknames that are NOT real accounts come from, in priority order. Only real, existing skins
         * are ever used: "mojang-accounts" (real accounts found through the official Mojang API), "skindb" (public
         * skin database returning Mojang-hosted textures) and "assets" (PNG files you put in assetDirectory).
         */
        public List<String> sources = new ArrayList<>(List.of("mojang-accounts", "skindb", "assets"));
        public String skinDbUrl = "https://api.skindb.net/skin/random?count=10";
        /** A borrowed real account's cape comes along with its skin (always from the same account). */
        public boolean donorCapes = true;
        public boolean allowDuplicateBorrowedSkins = false;
        public boolean backgroundDonorHarvest = true;
        public int donorTarget = 300;
        public int donorHarvestIntervalSeconds = 45;
        public int maxDonorProbeRequests = 40;
        public int donorProbeSeconds = 25;
        public int maxDonors = 5000;
        public boolean enableSkinLocking = true;
        public int maxCacheEntries = 256;
        public int downloadTimeoutSeconds = 10;
        /** Relative to config/nicks/. Your own skin PNGs: name.png (classic) / name.slim.png (slim). */
        public String assetDirectory = "skin_assets";
        public int maxSkinBytes = 262144;
        public List<String> allowedTextureHosts = new ArrayList<>(List.of("textures.minecraft.net"));
        public int profileCacheMinutes = 60;
        public int negativeCacheMinutes = 10;
    }

    public static final class Display {
        public boolean replaceTabList = true;
        public boolean replaceNametags = true;
        /** Chat, death/advancement/join messages, /msg, team chat (everything built from the player's display name). */
        public boolean replaceServerMessages = true;
    }

    public static final class Commands {
        /** Let vanilla commands accept nicknames wherever they take a player name. */
        public boolean aliasResolution = true;
    }

    public static final class Network {
        public boolean enableSync = true;
        public int maxTexturePayloadBytes = 131072;
        public int requestsPerMinute = 120;
        public int retryLimit = 3;
        public int retryBackoffMillis = 500;
        public int maxRateLimitWaitSeconds = 30;
        public String nameLookupUrl = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
        public String sessionProfileUrl = "https://sessionserver.mojang.com/session/minecraft/profile/";
        public String bulkLookupUrl = "https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname";
    }

    public static final class Storage {
        public boolean autoSave = true;
        public int saveDelaySeconds = 2;
    }

    // ---------------------------------------------------------------------

    /** Loads (and rewrites with defaults filled in) the config file. Corrupt files are kept as .corrupt and defaults used. */
    public static NicksConfig load(Path file) {
        NicksConfig cfg = new NicksConfig();
        try {
            if (Files.exists(file)) {
                try {
                    apply(cfg, Json.parseObject(Files.readString(file)));
                } catch (RuntimeException e) {
                    NicksLog.warn("config.json is not valid JSON, using defaults (old file kept as config.json.corrupt): " + e.getMessage());
                    Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            cfg.sanitize();
            Files.createDirectories(file.getParent());
            AtomicJsonFile.writeString(file, Json.write(cfg.toMap()));
        } catch (IOException e) {
            NicksLog.warn("Could not read/write config: " + e.getMessage());
            cfg.sanitize();
        }
        NicksLog.setDebug(cfg.debug);
        return cfg;
    }

    /** Re-read the file into THIS instance (components keep their reference). Throws before changing anything if the JSON is invalid. */
    public void reload(Path file) throws IOException {
        Map<String, Object> json = Files.exists(file) ? Json.parseObject(Files.readString(file)) : Map.of();
        apply(this, json);
        sanitize();
        NicksLog.setDebug(debug);
    }

    public void sanitize() {
        nickname.randomMinLength = Math.max(3, Math.min(16, nickname.randomMinLength));
        nickname.randomMaxLength = Math.max(nickname.randomMinLength, Math.min(16, nickname.randomMaxLength));
        nickname.historyLimit = Math.max(0, nickname.historyLimit);
        skin.maxCacheEntries = Math.max(8, skin.maxCacheEntries);
        skin.maxDonors = Math.max(10, skin.maxDonors);
        skin.donorTarget = Math.max(0, Math.min(skin.maxDonors, skin.donorTarget));
        skin.donorHarvestIntervalSeconds = Math.max(5, skin.donorHarvestIntervalSeconds);
        skin.maxDonorProbeRequests = Math.max(1, skin.maxDonorProbeRequests);
        skin.donorProbeSeconds = Math.max(1, skin.donorProbeSeconds);
        skin.downloadTimeoutSeconds = Math.max(1, skin.downloadTimeoutSeconds);
        skin.maxSkinBytes = Math.max(1024, Math.min(1_048_576, skin.maxSkinBytes));
        network.maxTexturePayloadBytes = Math.max(4096, Math.min(900_000, network.maxTexturePayloadBytes));
        network.requestsPerMinute = Math.max(1, network.requestsPerMinute);
        network.retryLimit = Math.max(1, Math.min(8, network.retryLimit));
        network.retryBackoffMillis = Math.max(0, network.retryBackoffMillis);
        storage.saveDelaySeconds = Math.max(0, storage.saveDelaySeconds);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        dump(this, m);
        return m;
    }

    // ---- reflection helpers (config only; no Minecraft types involved) ----

    private static boolean isSection(Field f) {
        return f.getType().getDeclaringClass() == NicksConfig.class;
    }

    private static void dump(Object o, Map<String, Object> out) {
        for (Field f : o.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                Object v = f.get(o);
                if (isSection(f)) {
                    Map<String, Object> sub = new LinkedHashMap<>();
                    dump(v, sub);
                    out.put(f.getName(), sub);
                } else {
                    out.put(f.getName(), v);
                }
            } catch (IllegalAccessException ignored) { }
        }
    }

    @SuppressWarnings("unchecked")
    private static void apply(Object target, Map<String, Object> src) {
        for (Field f : target.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Object v = src.get(f.getName());
            if (v == null) continue;
            try {
                Class<?> t = f.getType();
                if (isSection(f)) {
                    if (v instanceof Map<?, ?> m) apply(f.get(target), (Map<String, Object>) m);
                } else if (t == boolean.class && v instanceof Boolean b) f.setBoolean(target, b);
                else if (t == int.class && v instanceof Number n) f.setInt(target, n.intValue());
                else if (t == long.class && v instanceof Number n) f.setLong(target, n.longValue());
                else if (t == String.class && v instanceof String s) f.set(target, s);
                else if (t == List.class && v instanceof List<?> l) {
                    List<String> out = new ArrayList<>();
                    for (Object e : l) out.add(String.valueOf(e));
                    f.set(target, out);
                } else if (t == Map.class && v instanceof Map<?, ?> m) {
                    Map<String, String> out = new LinkedHashMap<>();
                    m.forEach((k, val) -> out.put(String.valueOf(k), String.valueOf(val)));
                    f.set(target, out);
                }
            } catch (IllegalAccessException ignored) { }
        }
    }
}
