# Task 3 Report: Versioned load / seed / migrate in LotteryConfigService

## Status
DONE

## File modified
- `src/main/java/com/habitrain/lottery/config/LotteryConfigService.java`

No call-site renames needed (`loadOrSeed` kept). `HabiLotteryMod` untouched.

## Changes

### Fields & accessors
- Added `skinsQuality` (`SkinsQualityConfig`) and `meta` (`ConfigMeta`) instance fields
- Added `getSkinsQuality()`, `getMeta()`
- Added `skinsQualityPath()`, `metaPath()` via `WorldLotteryPaths.configFile(...)`

### `ensureClientDefaults`
- Also loads bundled `skins_quality.json` into memory for title-screen / Mod Menu use

### `loadOrSeed` (body upgraded, name unchanged)
1. Detect on-disk version:
   - `meta.json` present → use `config_version`
   - no meta but any of pools/rates/grants/theme present → treat as legacy v1
   - nothing on disk → brand new (`-1`)
2. Brand new: `seedIfMissing` for pools/rates/grants/theme/skins_quality + `writeFreshMeta()`
3. `onDiskVersion < CURRENT_VERSION (2)`: call `migrate(fromVersion)`
4. Already current (or future): seed missing files; write meta if missing; warn if version > supported
5. Always re-read all six configs into memory and log `v{config_version}`, pool count, `coinPerDraw`

### `migrate(fromVersion)`
- Back up existing config files to `{world}/habitrain_lottery/config/backup_v{fromVersion}/`
- Force-replace `pools.json` from bundled defaults (`copyBundledForce`)
- Merge rates from backup (or current path):
  - if `coinPerDraw <= 10` → set to `160` and log migration
  - if `coinPerDraw > 10` → keep user value and log
- `seedIfMissing` grants/theme/skins_quality
- `writeFreshMeta()` with `CURRENT_VERSION=2` and `migratedAt=Instant.now()`

### Helpers
- `copyBundledForce(name)` — overwrite target from `data/habitrain_lottery/defaults/`
- `writeFreshMeta()` — write fresh `ConfigMeta` with current version + timestamp

### `saveAll`
- Still writes pools/rates/grants/theme only
- Does **not** rewrite/wipe `meta.json`
- Does **not** force-write `skins_quality.json` on every save

### Snapshot / export
- Left as-is (pools/rates/grants/theme only) — optional skins_quality network snapshot deferred

## Build
```
gradlew.bat clean build
→ BUILD SUCCESSFUL
```
JAR copied to `D:\Backup\mc mod\临时\`

## Notes / non-goals
- Player data never touched
- World-local config only under `{world}/habitrain_lottery/config/`
- No git commits
- Commits: none
