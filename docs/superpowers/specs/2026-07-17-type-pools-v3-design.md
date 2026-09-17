# Type-Based Lottery Pools v3 Design

**Date:** 2026-07-17  
**Mod:** habitrain_lottery  
**Status:** Approved

## Goals

1. Replace gold-centric pools with **type pools**: knife / gun / bat / grenade + all-random.
2. Probabilities:
   - Type pools: **30% skin / 70% coin**
   - All-random: **50% skin / 50% coin**
3. Skins may repeat; **duplicate skin always refunds flat 60 coins**.
4. New pool cover art `pool_bg0..4` for SRE LootInfoScreen.
5. Export configs to `临时`; build JAR.

## Pools

| ID | Name | Items | Prob |
|----|------|-------|------|
| 0 | 刀池 | all `knife/*` | 0.30 skins / 0.70 coin |
| 1 | 枪池 | all `gun/*` (from list `revolver/*`) | 0.30 / 0.70 |
| 2 | 棍池 | all `bat/*` | 0.30 / 0.70 |
| 3 | 手雷池 | all `grenade/*` | 0.30 / 0.70 |
| 4 | 完全随机 | all skins | 0.50 / 0.50 |

Gun entries must use `gun/` prefix (SRE + textures).

## Duplicate refund

- `RatesConfig.duplicateCoinFlat = 60` (default)
- `LotteryPoolMixin` scales duplicate path to flat 60 (ignore band formula)
- Pure coin band still uses SRE coin-card formula

## Config version

- CURRENT = **3**
- v2→v3: backup, force new pools.json, merge rates with duplicateCoinFlat=60, keep player data

## UI

- Keep LootInfoScreen
- Replace `pool_bg0.png`–`pool_bg4.png` with new type-themed covers
