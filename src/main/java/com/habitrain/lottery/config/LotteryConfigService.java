package com.habitrain.lottery.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.server.MinecraftServer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class LotteryConfigService {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final LotteryConfigService INSTANCE = new LotteryConfigService();
    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final List<String> CONFIG_FILES = List.of(
            "pools.json", "rates.json", "grants.json", "theme.json", "meta.json", "skins_quality.json");
    private static final boolean CONFIG_FSYNC = true;

    private PoolConfigModels.Root pools = new PoolConfigModels.Root();
    private RatesConfig rates = new RatesConfig();
    private GrantsConfig grants = new GrantsConfig();
    private ThemeConfig theme = new ThemeConfig();
    private SkinsQualityConfig skinsQuality = new SkinsQualityConfig();
    private ConfigMeta meta = new ConfigMeta();
    private boolean clientDefaultsLoaded;
    private boolean dirty;
    private boolean loadFailed;

    LotteryConfigService() {
    }

    /**
     * Load bundled default JSON into memory so Mod Menu works on the title screen
     * before any world/server config exists.
     */
    public void ensureClientDefaults() {
        if (clientDefaultsLoaded) {
            return;
        }
        clientDefaultsLoaded = true;
        try {
            PoolConfigModels.Root p = readBundled("pools.json", PoolConfigModels.Root.class);
            RatesConfig r = readBundled("rates.json", RatesConfig.class);
            GrantsConfig g = readBundled("grants.json", GrantsConfig.class);
            ThemeConfig t = readBundled("theme.json", ThemeConfig.class);
            SkinsQualityConfig sq = readBundled("skins_quality.json", SkinsQualityConfig.class);
            if (p != null) {
                pools = p;
                PoolConfigModels.ensureCoverIds(pools);
            }
            if (r != null) {
                rates = r;
            }
            if (g != null) {
                grants = g;
            }
            if (t != null) {
                theme = t;
            }
            if (sq != null) {
                skinsQuality = sq;
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Failed loading client default lottery configs: {}", e.toString());
        }
    }

    private <T> T readBundled(String name, Class<T> type) {
        try (InputStream in = bundledStream(name)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, type);
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Failed reading bundled {}: {}", name, e.toString());
            return null;
        }
    }

    public static LotteryConfigService get() {
        return INSTANCE;
    }

    public PoolConfigModels.Root getPools() {
        return pools;
    }

    public RatesConfig getRates() {
        return rates;
    }

    public GrantsConfig getGrants() {
        return grants;
    }

    public ThemeConfig getTheme() {
        return theme;
    }

    public SkinsQualityConfig getSkinsQuality() {
        return skinsQuality;
    }

    public ConfigMeta getMeta() {
        return meta;
    }

    public boolean isLoadFailed() {
        return loadFailed;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setPools(PoolConfigModels.Root pools) {
        this.pools = pools != null ? pools : new PoolConfigModels.Root();
        PoolConfigModels.ensureCoverIds(this.pools);
        dirty = true;
    }

    public void setRates(RatesConfig rates) {
        this.rates = rates != null ? rates : new RatesConfig();
        clampOpPermissionLevel(this.rates);
        dirty = true;
    }

    public void setGrants(GrantsConfig grants) {
        this.grants = grants != null ? grants : new GrantsConfig();
        dirty = true;
    }

    public void setTheme(ThemeConfig theme) {
        this.theme = theme != null ? theme : new ThemeConfig();
        dirty = true;
    }

    public Path poolsPath() {
        return WorldLotteryPaths.configFile("pools.json");
    }

    public Path ratesPath() {
        return WorldLotteryPaths.configFile("rates.json");
    }

    public Path grantsPath() {
        return WorldLotteryPaths.configFile("grants.json");
    }

    public Path themePath() {
        return WorldLotteryPaths.configFile("theme.json");
    }

    public Path skinsQualityPath() {
        return WorldLotteryPaths.configFile("skins_quality.json");
    }

    public Path metaPath() {
        return WorldLotteryPaths.configFile("meta.json");
    }

    public void loadOrSeed(MinecraftServer server) {
        if (!WorldLotteryPaths.ready()) {
            WorldLotteryPaths.init(server);
        }
        loadOrSeed(WorldLotteryPaths.configDir());
    }

    void loadOrSeed(Path configDir) {
        loadFailed = false;
        dirty = false;
        if (configDir == null) {
            loadFailed = true;
            HabiLotteryMod.LOGGER.error("loadOrSeed: config dir is null");
            return;
        }
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {
            loadFailed = true;
            HabiLotteryMod.LOGGER.error("Cannot create config dir {}", configDir, e);
            return;
        }

        Path metaFile = configDir.resolve("meta.json");
        boolean anyConfig = Files.isRegularFile(configDir.resolve("pools.json"))
                || Files.isRegularFile(configDir.resolve("rates.json"))
                || Files.isRegularFile(configDir.resolve("grants.json"))
                || Files.isRegularFile(configDir.resolve("theme.json"));
        int onDiskVersion = -1;
        AtomicJsonFiles.JsonLoad<ConfigMeta> metaProbe = AtomicJsonFiles.readJson(metaFile, ConfigMeta.class, GSON);
        if (metaProbe.ok() && metaProbe.value() != null) {
            onDiskVersion = metaProbe.value().config_version;
        } else if (isCorruptLoad(metaProbe, metaFile)) {
            loadFailed = true;
            HabiLotteryMod.LOGGER.error("Corrupt meta.json; refusing migrate/saveAll");
        } else if (anyConfig) {
            onDiskVersion = 1;
        }

        if (!loadFailed) {
            if (onDiskVersion < 0) {
                seedIfMissing(configDir, "pools.json");
                seedIfMissing(configDir, "rates.json");
                seedIfMissing(configDir, "grants.json");
                seedIfMissing(configDir, "theme.json");
                seedIfMissing(configDir, "skins_quality.json");
                writeFreshMeta(configDir);
            } else if (onDiskVersion < ConfigMeta.CURRENT_VERSION) {
                migrate(configDir, onDiskVersion);
            } else {
                seedIfMissing(configDir, "pools.json");
                seedIfMissing(configDir, "rates.json");
                seedIfMissing(configDir, "grants.json");
                seedIfMissing(configDir, "theme.json");
                seedIfMissing(configDir, "skins_quality.json");
                if (!Files.isRegularFile(metaFile) && !Files.isRegularFile(AtomicJsonFiles.bakPath(metaFile))) {
                    writeFreshMeta(configDir);
                }
                if (onDiskVersion > ConfigMeta.CURRENT_VERSION) {
                    HabiLotteryMod.LOGGER.warn("Config version {} > supported {}", onDiskVersion, ConfigMeta.CURRENT_VERSION);
                }
            }
        }

        pools = loadLive(configDir.resolve("pools.json"), PoolConfigModels.Root.class, pools, "pools.json");
        PoolConfigModels.ensureCoverIds(pools);
        rates = loadLive(configDir.resolve("rates.json"), RatesConfig.class, rates, "rates.json");
        clampOpPermissionLevel(rates);
        grants = loadLive(configDir.resolve("grants.json"), GrantsConfig.class, grants, "grants.json");
        theme = loadLive(configDir.resolve("theme.json"), ThemeConfig.class, theme, "theme.json");
        skinsQuality = loadLive(configDir.resolve("skins_quality.json"), SkinsQualityConfig.class, skinsQuality, "skins_quality.json");
        meta = loadLive(configDir.resolve("meta.json"), ConfigMeta.class, meta, "meta.json");
        HabiLotteryMod.LOGGER.info("Loaded lottery configs v{} (pools={}, coinPerDraw={}, loadFailed={})",
                meta.config_version,
                pools.Pools != null ? pools.Pools.size() : 0,
                rates.coinPerDraw(),
                loadFailed);
    }

    /**
     * Backup then apply grants/rates migrations. Does not replace pools.json
     * (3→4 does not change pool schema). Backup failure aborts before live writes.
     */
    boolean migrate(Path configDir, int fromVersion) {
        String ts = BACKUP_TS.format(Instant.now());
        Path backupDir = configDir.resolve("backup_v" + fromVersion + "_" + ts);
        if (Files.exists(backupDir)) {
            backupDir = configDir.resolve("backup_v" + fromVersion + "_" + ts + "_" + System.nanoTime());
        }
        if (!backupConfig(configDir, backupDir)) {
            HabiLotteryMod.LOGGER.error("Config backup failed; aborting migrate v{} (live files untouched)", fromVersion);
            return false;
        }

        RatesConfig oldRates = readPlainJson(backupDir.resolve("rates.json"), RatesConfig.class);
        if (oldRates == null) {
            oldRates = readPlainJson(configDir.resolve("rates.json"), RatesConfig.class);
        }
        RatesConfig merged = oldRates != null ? oldRates : new RatesConfig();
        int oldCoin = merged.coinPerDraw;
        if (oldCoin <= 10) {
            merged.coinPerDraw = 160;
            HabiLotteryMod.LOGGER.info("Migrated coinPerDraw {} -> 160", oldCoin);
        } else {
            HabiLotteryMod.LOGGER.info("Kept user coinPerDraw {}", oldCoin);
        }
        if (merged.duplicateCoinFlat <= 0) {
            merged.duplicateCoinFlat = 60;
            HabiLotteryMod.LOGGER.info("Migrated duplicateCoinFlat -> 60");
        }
        if (!writeJson(configDir.resolve("rates.json"), merged)) {
            HabiLotteryMod.LOGGER.error("Failed writing migrated rates.json; leaving meta at v{}", fromVersion);
            return false;
        }

        if (fromVersion < 4) {
            if (!copyBundledForce(configDir, "grants.json")) {
                HabiLotteryMod.LOGGER.error("Failed migrating grants.json; leaving meta at v{}", fromVersion);
                return false;
            }
            HabiLotteryMod.LOGGER.info("Migrated grants.json to faction win table (v4)");
        } else if (!seedIfMissing(configDir, "grants.json")) {
            HabiLotteryMod.LOGGER.error("Failed seeding grants.json during migrate");
            return false;
        }
        seedIfMissing(configDir, "theme.json");
        seedIfMissing(configDir, "skins_quality.json");
        if (!writeFreshMeta(configDir)) {
            HabiLotteryMod.LOGGER.error("Failed writing meta after migrate v{}", fromVersion);
            return false;
        }
        HabiLotteryMod.LOGGER.info("Migrated lottery config v{} -> v{}", fromVersion, ConfigMeta.CURRENT_VERSION);
        return true;
    }

    public boolean saveAll(MinecraftServer server) {
        if (!WorldLotteryPaths.ready()) {
            return false;
        }
        return saveAll(WorldLotteryPaths.configDir());
    }

    boolean saveAll(Path configDir) {
        if (configDir == null) {
            return false;
        }
        if (loadFailed) {
            HabiLotteryMod.LOGGER.error("Refusing saveAll: previous config load failed");
            return false;
        }
        if (!dirty) {
            return true;
        }
        if (pools == null || pools.Pools == null || pools.Pools.isEmpty()) {
            HabiLotteryMod.LOGGER.error("Refusing saveAll: empty Pools");
            return false;
        }
        boolean ok = writeJson(configDir.resolve("pools.json"), pools);
        ok &= writeJson(configDir.resolve("rates.json"), rates);
        ok &= writeJson(configDir.resolve("grants.json"), grants);
        ok &= writeJson(configDir.resolve("theme.json"), theme);
        if (ok) {
            dirty = false;
        }
        return ok;
    }

    public boolean savePools() {
        if (loadFailed || !WorldLotteryPaths.ready()) {
            return false;
        }
        if (pools == null || pools.Pools == null || pools.Pools.isEmpty()) {
            HabiLotteryMod.LOGGER.error("Refusing savePools: empty Pools");
            return false;
        }
        return writeJson(poolsPath(), pools);
    }

    public boolean saveRates() {
        if (loadFailed || !WorldLotteryPaths.ready()) {
            return false;
        }
        return writeJson(ratesPath(), rates);
    }

    public boolean saveGrants() {
        if (loadFailed || !WorldLotteryPaths.ready()) {
            return false;
        }
        return writeJson(grantsPath(), grants);
    }

    public boolean saveTheme() {
        if (loadFailed || !WorldLotteryPaths.ready()) {
            return false;
        }
        return writeJson(themePath(), theme);
    }

    public String exportAllJson() {
        return GSON.toJson(new Snapshot(pools, rates, grants, theme));
    }

    /**
     * JOIN/snapshot for non-OP clients: Enable pools + gacha rate subset + theme.
     * Omits grants and opPermissionLevel.
     */
    public String exportPublicJson() {
        PoolConfigModels.Root publicPools = new PoolConfigModels.Root();
        publicPools.Pools = new ArrayList<>();
        if (pools != null && pools.Pools != null) {
            for (PoolConfigModels.Pool p : pools.Pools) {
                if (p != null && p.Enable) {
                    publicPools.Pools.add(p);
                }
            }
        }
        PublicRates publicRates = new PublicRates();
        if (rates != null) {
            publicRates.drawCostMultiplier = rates.drawCostMultiplier;
            publicRates.duplicateCoinMultiplier = rates.duplicateCoinMultiplier;
            publicRates.duplicateCoinFlat = rates.duplicateCoinFlat;
            publicRates.coinPerDraw = rates.coinPerDraw;
        }
        PublicSnapshot snap = new PublicSnapshot();
        snap.pools = publicPools;
        snap.rates = publicRates;
        snap.theme = theme;
        return GSON.toJson(snap);
    }

    /**
     * Replace in-memory pools/rates/grants/theme from a network/editor snapshot.
     * Integrated host client and server share this JVM singleton — the host
     * client must not importAllJson (see LotteryClientNetwork ConfigSnapshotS2C).
     */
    public void importAllJson(String json) {
        Snapshot snap = GSON.fromJson(json, Snapshot.class);
        if (snap == null) {
            return;
        }
        if (snap.pools != null) {
            pools = snap.pools;
            PoolConfigModels.ensureCoverIds(pools);
        }
        if (snap.rates != null) {
            rates = snap.rates;
            clampOpPermissionLevel(rates);
        }
        if (snap.grants != null) {
            grants = snap.grants;
        }
        if (snap.theme != null) {
            theme = snap.theme;
        }
        dirty = true;
    }

    private static void clampOpPermissionLevel(RatesConfig r) {
        if (r == null) {
            return;
        }
        r.opPermissionLevel = Math.min(4, Math.max(1, r.opPermissionLevel));
    }

    public static final class Snapshot {
        public PoolConfigModels.Root pools;
        public RatesConfig rates;
        public GrantsConfig grants;
        public ThemeConfig theme;

        public Snapshot() {
        }

        public Snapshot(PoolConfigModels.Root pools, RatesConfig rates, GrantsConfig grants, ThemeConfig theme) {
            this.pools = pools;
            this.rates = rates;
            this.grants = grants;
            this.theme = theme;
        }
    }

    public static final class PublicRates {
        public double drawCostMultiplier = 1.0;
        public double duplicateCoinMultiplier = 1.0;
        public int duplicateCoinFlat;
        public int coinPerDraw;
    }

    public static final class PublicSnapshot {
        public PoolConfigModels.Root pools;
        public PublicRates rates;
        public ThemeConfig theme;
    }

    static boolean writeJson(Path path, Object value) {
        if (path == null || value == null) {
            return false;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Failed creating parent for {}", path, e);
            return false;
        }
        return AtomicJsonFiles.writeJson(path, value, GSON, CONFIG_FSYNC);
    }

    static boolean seedIfMissing(Path configDir, String name) {
        Path target = configDir.resolve(name);
        if (Files.isRegularFile(target) || Files.isRegularFile(AtomicJsonFiles.bakPath(target))) {
            return true;
        }
        return copyBundled(target, name, false);
    }

    static boolean copyBundledForce(Path configDir, String name) {
        return copyBundled(configDir.resolve(name), name, true);
    }

    private static boolean copyBundled(Path target, String name, boolean force) {
        try (InputStream in = bundledStream(name)) {
            if (in == null) {
                HabiLotteryMod.LOGGER.warn("Missing bundled {}", name);
                return false;
            }
            boolean ok = AtomicJsonFiles.copyFrom(in, target, CONFIG_FSYNC, true);
            if (ok) {
                HabiLotteryMod.LOGGER.info("{} world config {}", force ? "Replaced" : "Seeded", target);
            } else {
                HabiLotteryMod.LOGGER.error("Failed {} {}", force ? "force-seed" : "seeding", name);
            }
            return ok;
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Failed {} {}", force ? "force-seed" : "seeding", name, e);
            return false;
        }
    }

    private static InputStream bundledStream(String name) {
        return LotteryConfigService.class.getClassLoader()
                .getResourceAsStream("data/habitrain_lottery/defaults/" + name);
    }

    private boolean writeFreshMeta(Path configDir) {
        ConfigMeta m = new ConfigMeta();
        m.config_version = ConfigMeta.CURRENT_VERSION;
        m.migratedAt = Instant.now().toString();
        if (!writeJson(configDir.resolve("meta.json"), m)) {
            return false;
        }
        meta = m;
        return true;
    }

    private static boolean backupConfig(Path configDir, Path backupDir) {
        try {
            if (Files.exists(backupDir)) {
                HabiLotteryMod.LOGGER.error("Refusing to overwrite existing backup {}", backupDir);
                return false;
            }
            Files.createDirectories(backupDir);
            for (String name : CONFIG_FILES) {
                Path src = configDir.resolve(name);
                if (Files.isRegularFile(src)) {
                    Files.copy(src, backupDir.resolve(name));
                }
            }
            return true;
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Config backup failed", e);
            return false;
        }
    }

    private <T> T loadLive(Path path, Class<T> type, T previous, String label) {
        AtomicJsonFiles.JsonLoad<T> load = AtomicJsonFiles.readJson(path, type, GSON);
        if (load.ok() && load.value() != null) {
            if (load.usedBackup()) {
                HabiLotteryMod.LOGGER.warn("Restored {} from .bak", label);
            }
            return load.value();
        }
        if (isCorruptLoad(load, path)) {
            loadFailed = true;
            HabiLotteryMod.LOGGER.error("Corrupt {} (primary+bak unreadable); keeping previous in-memory, saveAll disabled",
                    label);
            return previous;
        }
        return previous;
    }

    /** AtomicJsonFiles reports missing-primary + unreadable bak as MISSING; treat as corrupt. */
    private static boolean isCorruptLoad(AtomicJsonFiles.JsonLoad<?> load, Path path) {
        if (load.corrupt()) {
            return true;
        }
        return load.isMissing() && Files.isRegularFile(AtomicJsonFiles.bakPath(path));
    }

    private static <T> T readPlainJson(Path path, Class<T> type) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Failed reading {}", path, e);
            return null;
        }
    }
}
