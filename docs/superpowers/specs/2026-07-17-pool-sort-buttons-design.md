# Pool Sort Buttons Design

**Date:** 2026-07-17  
**Mod:** habitrain_lottery  
**Status:** Approved (Approach A)

## Goal

Let OP reorder lottery pools in the config UI so both:

1. the config-page pool list, and  
2. the player-facing SRE loot sidebar  

reflect the same order.

## Context

- Config UI: `LotteryConfigRootScreen` 奖池 tab, left `poolList` with `+池` / `-池` only.
- Player UI: SRE `LotteryManager.sortPools()` sorts by **`PoolID` ascending**.
- Therefore list-order alone is not enough; **`PoolID` must move with display order**.

## Behavior

| Action | Effect |
|--------|--------|
| `↑` | Swap selected pool with previous neighbor: array index + `PoolID` |
| `↓` | Swap selected pool with next neighbor: array index + `PoolID` |
| At first / last | Corresponding button **disabled** (grey, no-op) |
| Selection | Follows the moved pool |
| Persist | Same as other edits: memory first; **保存到服务器** writes world config + SRE reload / client sync |
| Permissions | Same as `+池`/`-池`: offline editable; online requires OP |

## UI

Place `↑` / `↓` on the **same row** as `+池` / `-池` under the left pool list. Shrink button widths slightly so all four fit in the 120px left column.

## Non-goals

- Drag-and-drop reordering  
- Auto-save on each move  
- Changing SRE sort algorithm  
- Reassigning all IDs to `0..n-1` (only pairwise swap)

## Implementation surface

- Primary: `LotteryConfigRootScreen.buildPoolsTab` + small `moveSelectedPool(delta)` helper  
- Optional lock refresh after `applyOpWidgetLocks` so boundary disable survives OP unlock  
- No model schema change; `PoolConfigModels` unchanged

## Verification

1. Two+ pools: `↓` on first swaps with second; list labels and `PoolID` fields swap.  
2. First pool: `↑` disabled; last pool: `↓` disabled.  
3. Save to server → client loot sidebar order matches new `PoolID` order.  
4. Non-OP online: move buttons inactive.  
5. `./gradlew clean build` and copy JAR to `临时`.
