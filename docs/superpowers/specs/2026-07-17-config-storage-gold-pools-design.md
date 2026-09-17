# Config Storage Normalization & Gold Pools Design

**Date:** 2026-07-17  
**Mod:** habitrain_lottery (`哈比列车抽奖补齐`)  
**Status:** Approved

## Goals

1. Normalize data/config storage so **copying only the JAR** is enough: on world start the mod creates dirs and seeds missing configs from bundled defaults.
2. Versioned migration for existing worlds (`config_version`), with backups before rewrite.
3. Change coin→draw cost from **10 → 160**.
4. Rebuild lottery pools: **one pool per gold-quality skin** (18 pools), gold rate **1%**, other skins assigned by quality tiers with **cross-pool mutual exclusion**.
5. Export all usable default config files to `D:\Backup\mc mod\临时\` and document setup.

## Non-goals (this pass)

- Auto-regenerate `pools.json` from `skins_quality.json` at runtime (pools are static default JSON; quality table is documentation + future tooling).
- Moving player economy off world storage.
- Changing grant events or theme assets unless required for seed completeness.

## Architecture

### Storage layout (unchanged root, extended files)

```
{world}/habitrain_lottery/
  config/
    meta.json                 # config_version, migratedAt
    pools.json
    rates.json
    grants.json
    theme.json
    skins_quality.json        # NEW: type/id → quality
    backup_v{N}/              # created on migrate
  players/<uuid>.json
  history/<uuid>.jsonl
```

**Authoritative defaults:** JAR resources under  
`data/habitrain_lottery/defaults/`  
(`pools.json`, `rates.json`, `grants.json`, `theme.json`, `skins_quality.json`, `skins.json`, `skins_list.txt`, and a small `meta.json` template).

**Optional global skin override (existing):**  
`.minecraft/config/habitrain_lottery/skins.json` — registration only; not required to run.

### Runtime flow

1. `SERVER_STARTED` → `WorldLotteryPaths.init`
2. `LotteryConfigService.loadMigrateOrSeed(server)`:
   - ensure config dir
   - if no configs → seed all defaults, write `meta.json` with `config_version = CURRENT (2)`
   - if configs exist without meta → treat as **v1**
   - if `meta.config_version < CURRENT` → backup then migrate
   - if equal → load; seed only missing individual files
   - if greater → warn, best-effort load
3. `LotteryManagerBridge.applyWorldPools` syncs world `pools.json` → SRE `lottery_skin_data/lottery_pool.json` + `reload()`
4. Player store takeover unchanged

### Config version

- **CURRENT = 2**
- `meta.json`:

```json
{
  "config_version": 2,
  "migratedAt": "2026-07-17T00:00:00Z"
}
```

### v1 → v2 migration

1. Copy existing `pools.json`, `rates.json`, `grants.json`, `theme.json` (if present) into `config/backup_v1/`.
2. **Replace** `pools.json` with bundled v2 gold pools.
3. **Merge** `rates.json`: keep user fields; set `coinPerDraw = 160` when old value is missing or `≤ 10`; if user already set a custom value `> 10`, keep it and log.
4. **Keep** `grants.json` and `theme.json` as-is when present; seed only if missing.
5. **Seed** `skins_quality.json` if missing.
6. Write `meta.json` with `config_version: 2`.
7. Do **not** touch `players/` or `history/`.

### “JAR only” guarantee

Fresh world or missing tree:

- Creates `habitrain_lottery/{config,players,history}`
- Seeds all default JSON from the JAR
- Applies pools to SRE

No manual config copy required for lottery to function.

## Rates

| Field | New default |
|-------|-------------|
| `coinPerDraw` | **160** |

Also update:

- `RatesConfig.coinPerDraw` Java default
- Bundled `defaults/rates.json`
- Any design/docs that still say “10 coins = 1 draw”

## Gold skins (18)

SRE pool entries use abstract paths; revolvers appear as `gun/...` (mapped to `revolver` by `SkinTypeKeys`).

| PoolID | Item | PoolName |
|--------|------|----------|
| 0 | `bat/excalibur` | 金色·圣剑 |
| 1 | `bat/jin_gu_bang` | 金色·金箍棒 |
| 2 | `bat/platinum_arm` | 金色·白金之臂 |
| 3 | `bat/ultimate_gold_weapon` | 金色·终极黄金武器 |
| 4 | `knife/anubis` | 金色·阿努比斯 |
| 5 | `knife/echoium_sword` | 金色·回声合金剑 |
| 6 | `knife/galaxy_spark` | 金色·银河火花 |
| 7 | `knife/gamma_doppler_claw_knife` | 金色·伽马多普勒爪刀 |
| 8 | `knife/gold_claw_knife` | 金色·黄金爪刀 |
| 9 | `knife/golden_shear` | 金色·金剪刀 |
| 10 | `knife/quenched_titanium` | 金色·淬炼钛金 |
| 11 | `gun/blackgold` | 金色·黑金 |
| 12 | `gun/divine` | 金色·神圣 |
| 13 | `gun/gold_defibrillator` | 金色·金色除颤器 |
| 14 | `gun/golden_gun` | 金色·金枪 |
| 15 | `gun/golden_gyration` | 金色·黄金回旋 |
| 16 | `gun/white_gold_gun` | 金色·白金枪 |
| 17 | `gun/white_gun` | 金色·钛金枪 |

Each pool: `Enable: true`, `PoolType: "gold"`.

## Per-pool probability bands

Sum = 1.0:

| Band | Probability | ItemList |
|------|-------------|----------|
| Gold | **0.01** | This pool’s single gold skin |
| Epic | **0.04** | epic skins assigned to this pool |
| Rare | **0.12** | rare skins assigned to this pool |
| Uncommon | **0.28** | uncommon skins assigned to this pool |
| Common | **0.45** | common skins assigned to this pool |
| Coin | **0.10** | `["coin"]` |

If a non-gold band receives zero skins after distribution, `ItemList` is `["coin"]` so probability mass stays valid.

## Quality table (`skins_quality.json`)

```json
{
  "version": 1,
  "qualities": {
    "bat/excalibur": "gold",
    "bat/bamboo": "common"
  }
}
```

- Qualities: `common | uncommon | rare | epic | gold`
- **Gold:** fixed 18 IDs above
- **Remaining ~302 skins:** assigned offline into defaults with approximate distribution:
  - common ~45%
  - uncommon ~30%
  - rare ~18%
  - epic ~7%
- Heuristic for non-gold defaults (editable later): elevated keywords (`diamond`, `plasma`, `astral`, `legendary`, `divine`-like fantasy names, etc.) → higher tiers; meme/basic names → common. Deterministic sort by `type/id` before round-robin so rebuilds are stable.

### Mutual exclusion assignment

1. Partition non-gold skins by quality.
2. Within each quality, sort IDs lexicographically.
3. Round-robin into 18 pools (index `% 18`).
4. No non-gold skin appears in more than one pool.

## Code touchpoints

| Area | Change |
|------|--------|
| `LotteryConfigService` | `loadMigrateOrSeed`, backup, version checks, seed `skins_quality.json` + `meta.json` |
| `RatesConfig` / `defaults/rates.json` | `coinPerDraw = 160` |
| `defaults/pools.json` | Full rewrite: 18 gold pools with new bands |
| `defaults/skins_quality.json` | New |
| `defaults/meta.json` | New template version 2 |
| `README.md` | JAR-only behavior, 160 cost, config paths, `/hlt reload` |
| Export | Copy final defaults to `D:\Backup\mc mod\临时\habitrain_lottery_config\` + short `SETUP.txt` |

Optional small helper (build-time or one-shot script in repo) to regenerate pools from quality table — not required at runtime.

## Delivery / setup notes (user-facing)

After build:

1. Install `habitrain_lottery-*.jar` into `mods/` (with SRE + Fabric API).
2. Start world once → configs appear under `{world}/habitrain_lottery/config/`.
3. Optional: copy exported files from `临时/habitrain_lottery_config/` over world config, then `/hlt reload`.
4. Coin exchange: **160 coins = 1 draw**.
5. Edit pools/rates in world config; OP `/hlt reload` applies without full restart when supported by existing command.

## Verification

1. Delete world `habitrain_lottery/` folder → start server → all config files recreated; pools load; log shows seed.
2. Simulate v1 world (old pools + rates with `coinPerDraw: 10`, no meta) → backup created, pools replaced, `coinPerDraw` becomes 160, meta v2 written.
3. User rates with `coinPerDraw: 200` → remains 200 after migrate.
4. Pool JSON: 18 pools; each gold band probability 0.01; probabilities sum to 1; no duplicate non-gold IDs across pools.
5. `CoinToDrawService` uses 160 from loaded rates.
6. `./gradlew clean build` succeeds; JAR copied to `临时/`; config export present.

## Spec self-review

- No TBD placeholders for core behavior.
- Path choice matches user: world-local config + versioned migrate.
- coinPerDraw rule explicit (≤10 → 160, else keep).
- Gold list and probabilities explicit.
- Player data isolation explicit.
