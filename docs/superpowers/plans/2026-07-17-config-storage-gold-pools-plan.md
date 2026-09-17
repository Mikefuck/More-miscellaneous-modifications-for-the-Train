# Config Storage + Gold Pools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make JAR-only deploy auto-create/migrate world lottery configs, set coin→draw to 160, and ship 18 gold-centric pools (1% gold) with quality-tiered mutually exclusive fillers.

**Architecture:** Keep world-local `{world}/habitrain_lottery/` storage. Extend `LotteryConfigService` with `meta.json` versioning, backup-on-migrate, and seed for new `skins_quality.json`. Generate default `skins_quality.json` + `pools.json` offline with a deterministic Python script, embed results under `data/habitrain_lottery/defaults/`, export copies to `D:\Backup\mc mod\临时\habitrain_lottery_config\`.

**Tech Stack:** Fabric 1.21.1, Java 21, Gson, Python 3 for offline pool generation, Gradle Loom.

## Global Constraints

- Address user as Mike in user-facing notes.
- File access only under `D:\Backup\mc mod\` (never `backup\`).
- After any mod modification: `./gradlew clean build` then copy JAR to `D:\Backup\mc mod\临时\`.
- `config_version` CURRENT = **2**.
- `coinPerDraw` default = **160**.
- Gold probability per pool = **0.01**; bands: epic 0.04, rare 0.12, uncommon 0.28, common 0.45, coin 0.10.
- 18 gold skins exactly as in spec; revolvers in pools use `gun/` prefix.
- Player data (`players/`, `history/`) never rewritten by config migration.
- No git repo in this project dir — skip git commit steps; still complete file changes.

## File map

| File | Responsibility |
|------|----------------|
| `tools/generate_pools.py` | Build `skins_quality.json` + `pools.json` from `skins_list.txt` |
| `src/main/resources/data/habitrain_lottery/defaults/meta.json` | Default meta template v2 |
| `src/main/resources/data/habitrain_lottery/defaults/skins_quality.json` | Quality map |
| `src/main/resources/data/habitrain_lottery/defaults/pools.json` | 18 gold pools |
| `src/main/resources/data/habitrain_lottery/defaults/rates.json` | coinPerDraw 160 |
| `src/main/java/.../config/ConfigMeta.java` | meta.json model + CURRENT version |
| `src/main/java/.../config/SkinsQualityConfig.java` | skins_quality model |
| `src/main/java/.../config/RatesConfig.java` | default coinPerDraw 160 |
| `src/main/java/.../config/LotteryConfigService.java` | seed + migrate + load |
| `src/main/java/.../HabiLotteryMod.java` | call `loadMigrateOrSeed` (if renamed) |
| `README.md` | setup / 160 / migration notes |
| `D:\Backup\mc mod\临时\habitrain_lottery_config\*` | exported configs + SETUP.txt |

---

### Task 1: Offline quality table + pools generator

**Files:**
- Create: `tools/generate_pools.py`
- Create/Overwrite: `src/main/resources/data/habitrain_lottery/defaults/skins_quality.json`
- Create/Overwrite: `src/main/resources/data/habitrain_lottery/defaults/pools.json`
- Create: `src/main/resources/data/habitrain_lottery/defaults/meta.json`
- Modify: `src/main/resources/data/habitrain_lottery/defaults/rates.json` (`coinPerDraw: 160`)

**Interfaces:**
- Consumes: `defaults/skins_list.txt` (320 lines `type/id`)
- Produces: quality map + 18 pools JSON matching `PoolConfigModels`

- [ ] **Step 1: Write generator script**

Create `tools/generate_pools.py` with this logic:

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate skins_quality.json and pools.json for habitrain_lottery defaults."""
from __future__ import annotations
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = ROOT / "src/main/resources/data/habitrain_lottery/defaults"
LIST_PATH = DEFAULTS / "skins_list.txt"

GOLD = [
    ("bat/excalibur", "金色·圣剑"),
    ("bat/jin_gu_bang", "金色·金箍棒"),
    ("bat/platinum_arm", "金色·白金之臂"),
    ("bat/ultimate_gold_weapon", "金色·终极黄金武器"),
    ("knife/anubis", "金色·阿努比斯"),
    ("knife/echoium_sword", "金色·回声合金剑"),
    ("knife/galaxy_spark", "金色·银河火花"),
    ("knife/gamma_doppler_claw_knife", "金色·伽马多普勒爪刀"),
    ("knife/gold_claw_knife", "金色·黄金爪刀"),
    ("knife/golden_shear", "金色·金剪刀"),
    ("knife/quenched_titanium", "金色·淬炼钛金"),
    ("gun/blackgold", "金色·黑金"),  # list file uses revolver/; pool uses gun/
    ("gun/divine", "金色·神圣"),
    ("gun/gold_defibrillator", "金色·金色除颤器"),
    ("gun/golden_gun", "金色·金枪"),
    ("gun/golden_gyration", "金色·黄金回旋"),
    ("gun/white_gold_gun", "金色·白金枪"),
    ("gun/white_gun", "金色·钛金枪"),
]

# Map pool gun/* to skins_list revolver/*
def list_key(pool_key: str) -> str:
    if pool_key.startswith("gun/"):
        return "revolver/" + pool_key.split("/", 1)[1]
    return pool_key

EPIC_HINTS = (
    "plasma", "astral", "excalibur", "platinum", "divine", "legend", "ultimate",
    "echoium", "galaxy", "quenched", "dragon", "purgatory", "between_limits",
    "anubis", "black_king", "snow_king", "jin_gu", "white_gold", "blackgold",
)
RARE_HINTS = (
    "diamond", "gold", "golden", "titanium", "rune", "ice_", "blood", "gamma",
    "doppler", "fractal", "emperor", "tyrant", "devil", "halo", "omega", "crystal",
    "amethyst", "obsidian", "void", "neon", "cyber", "royal", "king", "queen",
)
UNCOMMON_HINTS = (
    "iron", "steel", "silver", "copper", "advanced", "combat", "composite",
    "wrench", "hammer", "axe", "sword", "claw", "pipe", "cylinder", "guitar",
    "musical", "instrument", "pickaxe", "sickle", "bat_", "revolver",
)

def score_quality(skin_id: str) -> str:
    s = skin_id.lower()
    name = s.split("/", 1)[-1]
    if any(h in s for h in EPIC_HINTS):
        return "epic"
    if any(h in s for h in RARE_HINTS):
        return "rare"
    if any(h in name for h in UNCOMMON_HINTS):
        return "uncommon"
    return "common"


def rebalance(qualities: dict[str, str]) -> None:
    """Nudge non-gold distribution toward ~45/30/18/7 without touching gold."""
    nongold = [k for k, v in qualities.items() if v != "gold"]
    nongold.sort()
    n = len(nongold)
    # target counts
    n_epic = max(1, round(n * 0.07))
    n_rare = max(1, round(n * 0.18))
    n_uncommon = max(1, round(n * 0.30))
    # rank by heuristic rank then id
    rank = {"epic": 3, "rare": 2, "uncommon": 1, "common": 0}
    ordered = sorted(nongold, key=lambda k: (-rank[qualities[k]], k))
    for i, k in enumerate(ordered):
        if i < n_epic:
            qualities[k] = "epic"
        elif i < n_epic + n_rare:
            qualities[k] = "rare"
        elif i < n_epic + n_rare + n_uncommon:
            qualities[k] = "uncommon"
        else:
            qualities[k] = "common"


def main() -> None:
    raw = LIST_PATH.read_text(encoding="utf-8-sig").splitlines()
    skins = [ln.strip().replace("\\", "/") for ln in raw if ln.strip()]
    gold_list_keys = {list_key(g) for g, _ in GOLD}
    gold_pool_keys = {g for g, _ in GOLD}

    qualities: dict[str, str] = {}
    for s in skins:
        if s in gold_list_keys:
            # store under pool key for gold guns
            pool = s
            if s.startswith("revolver/"):
                pool = "gun/" + s.split("/", 1)[1]
            qualities[pool] = "gold"
        else:
            qualities[s] = score_quality(s)
    # ensure all 18 gold present even if list uses revolver
    for g, _ in GOLD:
        qualities[g] = "gold"
    rebalance(qualities)

    # Build assignment: non-gold by quality, round-robin 18 pools
    by_q: dict[str, list[str]] = defaultdict(list)
    for k, q in qualities.items():
        if q == "gold":
            continue
        by_q[q].append(k)
    for q in by_q:
        by_q[q].sort()

    pool_items: list[dict[str, list[str]]] = [
        {"epic": [], "rare": [], "uncommon": [], "common": []} for _ in range(18)
    ]
    for q in ("epic", "rare", "uncommon", "common"):
        for i, skin in enumerate(by_q.get(q, [])):
            pool_items[i % 18][q].append(skin)

    pools = []
    for idx, (gkey, gname) in enumerate(GOLD):
        bands = []
        def band(p: float, items: list[str]):
            return {"Probability": p, "ItemList": items if items else ["coin"]}
        bands.append(band(0.01, [gkey]))
        bands.append(band(0.04, pool_items[idx]["epic"]))
        bands.append(band(0.12, pool_items[idx]["rare"]))
        bands.append(band(0.28, pool_items[idx]["uncommon"]))
        bands.append(band(0.45, pool_items[idx]["common"]))
        bands.append(band(0.10, ["coin"]))
        pools.append({
            "PoolID": idx,
            "Enable": True,
            "PoolName": gname,
            "PoolType": "gold",
            "QualityListGroup": bands,
        })

    quality_out = {"version": 1, "qualities": dict(sorted(qualities.items()))}
    pools_out = {"Pools": pools}

    (DEFAULTS / "skins_quality.json").write_text(
        json.dumps(quality_out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (DEFAULTS / "pools.json").write_text(
        json.dumps(pools_out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    # validation
    assert len(pools) == 18
    seen = set()
    for p in pools:
        probs = sum(b["Probability"] for b in p["QualityListGroup"])
        assert abs(probs - 1.0) < 1e-9, probs
        assert p["QualityListGroup"][0]["Probability"] == 0.01
        for b in p["QualityListGroup"][1:5]:
            for it in b["ItemList"]:
                if it == "coin":
                    continue
                assert it not in seen, it
                seen.add(it)
                assert qualities.get(it) != "gold" or it == p["QualityListGroup"][0]["ItemList"][0]
    print("OK pools=18 non_gold_unique=", len(seen), "qualities=", len(qualities))

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run generator**

Run:
```bat
python tools\generate_pools.py
```
Expected stdout like: `OK pools=18 non_gold_unique=... qualities=...`

- [ ] **Step 3: Write meta template + rates**

`defaults/meta.json`:
```json
{
  "config_version": 2,
  "migratedAt": ""
}
```

`defaults/rates.json` — set `"coinPerDraw": 160` (keep other fields).

- [ ] **Step 4: Sanity-check outputs**

Run:
```bat
python -c "import json;from pathlib import Path;p=json.loads(Path('src/main/resources/data/habitrain_lottery/defaults/pools.json').read_text(encoding='utf-8'));print(len(p['Pools']), p['Pools'][0]['PoolName'], p['Pools'][0]['QualityListGroup'][0]);r=json.loads(Path('src/main/resources/data/habitrain_lottery/defaults/rates.json').read_text(encoding='utf-8'));print('coin',r['coinPerDraw'])"
```
Expected: `18`, first pool gold band 0.01, `coin 160`.

---

### Task 2: Config models + Rates default 160

**Files:**
- Create: `src/main/java/com/habitrain/lottery/config/ConfigMeta.java`
- Create: `src/main/java/com/habitrain/lottery/config/SkinsQualityConfig.java`
- Modify: `src/main/java/com/habitrain/lottery/config/RatesConfig.java`

**Interfaces:**
- Produces: `ConfigMeta.CURRENT_VERSION = 2`, `ConfigMeta` fields; `SkinsQualityConfig.qualities` map

- [ ] **Step 1: Add ConfigMeta**

```java
package com.habitrain.lottery.config;

public final class ConfigMeta {
    public static final int CURRENT_VERSION = 2;

    public int config_version = CURRENT_VERSION;
    public String migratedAt = "";
}
```

- [ ] **Step 2: Add SkinsQualityConfig**

```java
package com.habitrain.lottery.config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SkinsQualityConfig {
    public int version = 1;
    public Map<String, String> qualities = new LinkedHashMap<>();
}
```

- [ ] **Step 3: Change RatesConfig default**

In `RatesConfig.java`, set:
```java
public int coinPerDraw = 160;
```

---

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

### Task 4: README + export configs to 临时

**Files:**
- Modify: `README.md`
- Create: `D:\Backup\mc mod\临时\habitrain_lottery_config\SETUP.txt`
- Copy: defaults JSON into that folder

- [ ] **Step 1: Update README sections**

Document:
- World layout includes `meta.json`, `skins_quality.json`, `backup_vN/`
- JAR-only auto-seed + version migrate
- **160 coins = 1 draw**
- How to edit configs + `/hlt reload`
- Gold pool design summary (18 pools, 1% gold)

- [ ] **Step 2: Export**

```bat
mkdir "D:\Backup\mc mod\临时\habitrain_lottery_config"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\pools.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\rates.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\grants.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\theme.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\skins_quality.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\meta.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
```

Write `SETUP.txt` in Chinese explaining:
1. 只装 JAR 即可，进世界自动生成配置  
2. 若要手动覆盖：把本目录文件复制到 `{世界}/habitrain_lottery/config/` 后执行 `/hlt reload`  
3. 160 金币 = 1 抽  
4. 旧世界会备份到 `backup_v1/` 并迁移  

---

### Task 5: Full build + verify

**Files:** none new

- [ ] **Step 1: Validate pools with Python**

```bat
python -c "import json;from pathlib import Path;from collections import Counter
p=json.loads(Path('src/main/resources/data/habitrain_lottery/defaults/pools.json').read_text(encoding='utf-8'))
assert len(p['Pools'])==18
seen=set(); gold=set()
for pool in p['Pools']:
    bands=pool['QualityListGroup']
    assert abs(sum(b['Probability'] for b in bands)-1)<1e-9
    assert bands[0]['Probability']==0.01
    assert len(bands[0]['ItemList'])==1
    g=bands[0]['ItemList'][0]; assert g not in gold; gold.add(g)
    for b in bands[1:5]:
        for it in b['ItemList']:
            if it=='coin': continue
            assert it not in seen; seen.add(it)
print('gold',len(gold),'filler',len(seen))"
```

- [ ] **Step 2: Build and copy JAR**

```bat
gradlew.bat clean build
```
Expected: BUILD SUCCESSFUL  
Then ensure JAR is in `D:\Backup\mc mod\临时\` (build.gradle may already copy; if not, copy `build\libs\habitrain_lottery-*.jar` excluding `-sources`).

- [ ] **Step 3: Final user report**

Tell Mike:
- What changed
- Where configs are
- How to set up (JAR-only vs manual copy from 临时)
- 160 exchange
- Migration behavior for old worlds

---

## Spec coverage checklist

| Spec requirement | Task |
|------------------|------|
| World-local config layout | Task 3 (paths), Task 4 docs |
| JAR seed missing files | Task 3 `seedIfMissing` |
| meta config_version=2 | Task 1 meta, Task 2/3 |
| v1 backup + migrate | Task 3 |
| coinPerDraw 160 default + ≤10 migrate | Task 1 rates, Task 2, Task 3 |
| 18 gold pools, 1% gold, band split | Task 1 generator |
| Mutual exclusion by quality | Task 1 generator |
| skins_quality.json | Task 1, Task 3 seed |
| Export to 临时 + setup notes | Task 4 |
| Build + copy JAR | Task 5 |
| Player data untouched | Task 3 migrate scope |

## Self-review notes

- No TBD steps; generator script is complete.
- Method name kept as `loadOrSeed` to avoid missing call sites (`HabiLotteryMod`, `LotteryManagerBridge.reloadFromDisk`).
- Gold gun keys use `gun/` in pools/quality; list file uses `revolver/` — generator maps both.
