# Lottery Economy, Coin-to-Draw, Login Calendar Design

**Date:** 2026-07-17  
**Mod:** habitrain_lottery (`哈比列车抽奖补齐`)

## Goals

1. Duplicate skin → coins uses SRE base formula × `duplicateCoinMultiplier`.
2. 10 coins buy 1 draw via official LootInfoScreen button (`sre:loot coin2lottery`).
3. Consecutive login auto-grants `min(streak, 10)` draws on join (UTC day).
4. Multi-tile black-concrete calendar block shows month grid + login marks + streak.

## Design summary

- World JSON remains authoritative (`PlayerLotteryStore`).
- Do not modify SRE jar; use mixins/commands/network.
- `RatesConfig.coinPerDraw` default 10; `duplicateCoinMultiplier` wired into roll path.
- Command namespace: `sre` literal `loot` then `coin2lottery` (matches client sendCommand).
- Login fields on `PlayerLotteryData`; `LoginRewardService` on join.
- Block `login_calendar`: FACING, single display face, back face use for status; same-facing 4-connected tiles tile one calendar via BER.

## Verification

See implementation plan / parent plan verification section.
