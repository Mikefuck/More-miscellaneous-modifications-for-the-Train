### Task 3: Versioned load / seed / migrate in LotteryConfigService

**Files:**
- Modify: `src/main/java/com/habitrain/lottery/config/LotteryConfigService.java`
- Modify: `src/main/java/com/habitrain/lottery/HabiLotteryMod.java` (only if method rename)

**Interfaces:**
- Consumes: `ConfigMeta`, bundled defaults including `skins_quality.json` / `meta.json`
- Produces: `loadOrSeed` behavior upgraded to migrate (keep method name `loadOrSeed` to avoid call-site churn, or rename both)

- [ ] **Step 1: Extend service fields & paths**

Add:
```java
private SkinsQualityConfig skinsQuality = new SkinsQualityConfig();
private ConfigMeta meta = new ConfigMeta();

public SkinsQualityConfig getSkinsQuality() { return skinsQuality; }
public ConfigMeta getMeta() { return meta; }

public Path skinsQualityPath() { return WorldLotteryPaths.configFile("skins_quality.json"); }
public Path metaPath() { return WorldLotteryPaths.configFile("meta.json"); }
```

Include `skins_quality` in `ensureClientDefaults`, `exportAllJson` / `importAllJson` Snapshot if practical (optional for Mod Menu; minimum: load on server).

- [ ] **Step 2: Replace loadOrSeed body**

Implement algorithm:

```java
public void loadOrSeed(MinecraftServer server) {
    if (!WorldLotteryPaths.ready()) {
        WorldLotteryPaths.init(server);
    }
    Path metaFile = metaPath();
    boolean anyConfig = Files.isRegularFile(poolsPath()) || Files.isRegularFile(ratesPath())
            || Files.isRegularFile(grantsPath()) || Files.isRegularFile(themePath());
    int onDiskVersion = -1;
    if (Files.isRegularFile(metaFile)) {
        ConfigMeta m = readJson(metaFile, ConfigMeta.class, null);
        if (m != null) onDiskVersion = m.config_version;
    } else if (anyConfig) {
        onDiskVersion = 1; // legacy
    }

    if (onDiskVersion < 0) {
        // brand new
        seedIfMissing("pools.json");
        seedIfMissing("rates.json");
        seedIfMissing("grants.json");
        seedIfMissing("theme.json");
        seedIfMissing("skins_quality.json");
        writeFreshMeta();
    } else if (onDiskVersion < ConfigMeta.CURRENT_VERSION) {
        migrate(onDiskVersion);
    } else {
        seedIfMissing("pools.json");
        seedIfMissing("rates.json");
        seedIfMissing("grants.json");
        seedIfMissing("theme.json");
        seedIfMissing("skins_quality.json");
        if (!Files.isRegularFile(metaFile)) writeFreshMeta();
        if (onDiskVersion > ConfigMeta.CURRENT_VERSION) {
            HabiLotteryMod.LOGGER.warn("Config version {} > supported {}", onDiskVersion, ConfigMeta.CURRENT_VERSION);
        }
    }

    pools = readJson(poolsPath(), PoolConfigModels.Root.class, new PoolConfigModels.Root());
    rates = readJson(ratesPath(), RatesConfig.class, new RatesConfig());
    grants = readJson(grantsPath(), GrantsConfig.class, new GrantsConfig());
    theme = readJson(themePath(), ThemeConfig.class, new ThemeConfig());
    skinsQuality = readJson(skinsQualityPath(), SkinsQualityConfig.class, new SkinsQualityConfig());
    meta = readJson(metaPath(), ConfigMeta.class, new ConfigMeta());
    HabiLotteryMod.LOGGER.info("Loaded lottery configs v{} (pools={}, coinPerDraw={})",
            meta.config_version,
            pools.Pools != null ? pools.Pools.size() : 0,
            rates.coinPerDraw());
}
```

- [ ] **Step 3: Implement backup + migrate v1→v2**

```java
private void migrate(int fromVersion) {
    Path backupDir = WorldLotteryPaths.configDir().resolve("backup_v" + fromVersion);
    try {
        Files.createDirectories(backupDir);
        for (String name : List.of("pools.json", "rates.json", "grants.json", "theme.json", "meta.json", "skins_quality.json")) {
            Path src = WorldLotteryPaths.configFile(name);
            if (Files.isRegularFile(src)) {
                Files.copy(src, backupDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    } catch (IOException e) {
        HabiLotteryMod.LOGGER.error("Config backup failed", e);
    }

    // v1 -> v2 (and any fromVersion < 2)
    // Replace pools with bundled default
    copyBundledForce("pools.json");

    // Merge rates: load old from backup if present else current path
    RatesConfig oldRates = readJson(backupDir.resolve("rates.json"), RatesConfig.class, null);
    if (oldRates == null) {
        oldRates = readJson(ratesPath(), RatesConfig.class, new RatesConfig());
    }
    RatesConfig bundled = readBundled("rates.json", RatesConfig.class);
    if (bundled == null) bundled = new RatesConfig();
    RatesConfig merged = oldRates != null ? oldRates : new RatesConfig();
    int oldCoin = merged.coinPerDraw;
    if (oldCoin <= 10) {
        merged.coinPerDraw = 160;
        HabiLotteryMod.LOGGER.info("Migrated coinPerDraw {} -> 160", oldCoin);
    } else {
        HabiLotteryMod.LOGGER.info("Kept user coinPerDraw {}", oldCoin);
    }
    // ensure new fields exist if any future — for now write merged
    writeJson(ratesPath(), merged);

    seedIfMissing("grants.json");
    seedIfMissing("theme.json");
    seedIfMissing("skins_quality.json");
    // if skins_quality missing after seedIfMissing ok; if we want always refresh quality table on v2: copyBundledForce only when missing
    writeFreshMeta();
    HabiLotteryMod.LOGGER.info("Migrated lottery config v{} -> v{}", fromVersion, ConfigMeta.CURRENT_VERSION);
}

private void copyBundledForce(String name) {
    Path target = WorldLotteryPaths.configFile(name);
    try (InputStream in = LotteryConfigService.class.getClassLoader()
            .getResourceAsStream("data/habitrain_lottery/defaults/" + name)) {
        if (in == null) {
            HabiLotteryMod.LOGGER.warn("Missing bundled {}", name);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
        HabiLotteryMod.LOGGER.error("Failed force-seed {}", name, e);
    }
}

private void writeFreshMeta() {
    ConfigMeta m = new ConfigMeta();
    m.config_version = ConfigMeta.CURRENT_VERSION;
    m.migratedAt = java.time.Instant.now().toString();
    writeJson(metaPath(), m);
}
```

Also update `seedIfMissing` list usage for `skins_quality.json` (already generic by name).

Update `saveAll` to also write `skins_quality` only if you keep it mutable; meta usually not rewritten on normal save except migration. Minimum: do not wipe meta on `saveAll`.

- [ ] **Step 4: Compile check**

Run:
```bat
gradlew.bat compileJava
```
Expected: BUILD SUCCESSFUL

---
