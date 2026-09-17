# Task 1 Report: Offline quality table + pools generator

**Status:** DONE  
**Date:** 2026-07-17  
**Scope:** Default JSON data + rates/meta only (no Java)

## What was done

1. Created `tools/generate_pools.py` (verbatim from brief).
2. Ran generator successfully.
3. Created `src/main/resources/data/habitrain_lottery/defaults/meta.json`.
4. Updated `src/main/resources/data/habitrain_lottery/defaults/rates.json` (`coinPerDraw: 10` → `160`).
5. Generated:
   - `src/main/resources/data/habitrain_lottery/defaults/skins_quality.json`
   - `src/main/resources/data/habitrain_lottery/defaults/pools.json`

## Generator stdout

```
OK pools=18 non_gold_unique= 302 qualities= 320
```

## Sanity checks

| Check | Result |
|-------|--------|
| Pool count | 18 |
| First pool name | 金色·圣剑 |
| First gold band | `{"Probability": 0.01, "ItemList": ["bat/excalibur"]}` |
| `coinPerDraw` | 160 |
| meta.json | `{"config_version": 2, "migratedAt": ""}` |
| Probability sum per pool | 1.0 (0.01+0.04+0.12+0.28+0.45+0.10) |
| Gold skins | 18, all present |
| Non-gold uniqueness across pools | 302 unique (no duplicates) |
| Qualities total | 320 |

### Quality distribution (post-rebalance)

| Quality | Count | Notes |
|---------|-------|-------|
| gold | 18 | Fixed GOLD list |
| epic | 21 | ~7% of 302 non-gold |
| rare | 54 | ~18% of 302 |
| uncommon | 91 | ~30% of 302 |
| common | 136 | ~45% of 302 |

### Gold keys (pool form)

```
bat/excalibur, bat/jin_gu_bang, bat/platinum_arm, bat/ultimate_gold_weapon,
knife/anubis, knife/echoium_sword, knife/galaxy_spark, knife/gamma_doppler_claw_knife,
knife/gold_claw_knife, knife/golden_shear, knife/quenched_titanium,
gun/blackgold, gun/divine, gun/gold_defibrillator, gun/golden_gun,
gun/golden_gyration, gun/white_gold_gun, gun/white_gun
```

Revolver golds from `skins_list.txt` (`revolver/*`) correctly remapped to pool keys `gun/*` in both quality map and pools.

## Files touched

| Path | Action |
|------|--------|
| `tools/generate_pools.py` | Create |
| `src/main/resources/data/habitrain_lottery/defaults/skins_quality.json` | Create/Overwrite |
| `src/main/resources/data/habitrain_lottery/defaults/pools.json` | Create/Overwrite |
| `src/main/resources/data/habitrain_lottery/defaults/meta.json` | Create |
| `src/main/resources/data/habitrain_lottery/defaults/rates.json` | Modify (`coinPerDraw: 160`) |

## Self-review

- Script matches brief; no fixes required.
- Gold band always single gold skin at 0.01; coin band 0.10.
- Empty non-gold bands fall back to `["coin"]` via `band()` helper (acceptable per script).
- Non-gold revolvers remain `revolver/*` in qualities/pools; only the 7 gold guns use `gun/*` — intentional per brief comment.
- No Java migration (out of scope).
- No git commit (per task instruction).
- Full mod build skipped (not required for this data-only task).

## Concerns

None blocking. Minor notes only:

1. Windows console may garble Chinese pool names when printing; UTF-8 file content is correct.
2. Rebalance redistributes *counts* toward ~7/18/30/45 for epic/rare/uncommon/common among non-gold; pool *probabilities* remain fixed at 0.01/0.04/0.12/0.28/0.45/0.10 as specified.
3. Existing `pools.json` was fully overwritten by generator output (expected).

## Commits

none (no git)
