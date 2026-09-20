package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.config.PoolConfigModels;
import com.habitrain.lottery.storage.SkinTypeKeys;

import java.util.ArrayList;
import java.util.Collection;

/** Builds a runtime-only pool copy containing skin API contributions. */
public final class SkinPoolInjector {
    private SkinPoolInjector() {
    }

    public static PoolConfigModels.Root withRegisteredSkins(PoolConfigModels.Root source) {
        PoolConfigModels.Root copy = copyWithSkins(source, HabiSkinApi.registrations());
        if (copy == null || copy.Pools == null) return copy;
        for (var pool : copy.Pools) {
            if (pool == null || pool.QualityListGroup == null) continue;
            boolean hasReward = false;
            for (var band : pool.QualityListGroup) {
                if (band == null) continue;
                if (band.ItemList == null) band.ItemList = new ArrayList<>();
                band.ItemList.removeIf(entry -> !SkinLotteryRewards.isCoin(entry) && HabiSkinApi.fromEntry(entry).isEmpty());
                if (!band.ItemList.isEmpty()) hasReward = true;
            }
            if (!hasReward) { pool.Enable = false; continue; }
            // Preserve quality indices across the lottery display protocol.
            for (var band : pool.QualityListGroup) {
                if (band != null && band.ItemList.isEmpty()) band.ItemList.add("coin");
            }
        }
        return copy;
    }

    static PoolConfigModels.Root copyWithSkins(
            PoolConfigModels.Root source, Collection<SkinDefinition> definitions) {
        if (source == null) {
            return null;
        }
        PoolConfigModels.Root copy = deepCopy(source);
        if (copy.Pools == null || definitions == null || definitions.isEmpty()) {
            return copy;
        }
        for (SkinDefinition definition : definitions) {
            if (definition == null || definition.lotteryPlacements().isEmpty()) {
                continue;
            }
            String entry = definition.lotteryEntry();
            for (SkinDefinition.LotteryPlacement placement : definition.lotteryPlacements()) {
                for (int poolIndex = 0; poolIndex < copy.Pools.size(); poolIndex++) {
                    PoolConfigModels.Pool pool = copy.Pools.get(poolIndex);
                    if (!matches(pool, placement.poolType())) {
                        continue;
                    }
                    if (pool.QualityListGroup == null
                            || placement.qualityBand() >= pool.QualityListGroup.size()) {
                        continue;
                    }
                    PoolConfigModels.QualityBand band = pool.QualityListGroup.get(placement.qualityBand());
                    if (band == null) {
                        continue;
                    }
                    if (band.ItemList == null) {
                        band.ItemList = new ArrayList<>();
                    }
                    // Dedup is per band, not per pool: one skin may deliberately be
                    // declared in several quality bands of the same pool
                    // (.addToPool("knife", 0).addToPool("knife", 5)). Only the target
                    // band decides whether the entry is already present, and the
                    // untouched source config is checked too because the copy already
                    // carries the world's entries.
                    if (band.ItemList.contains(entry) || sourceHasEntry(source, poolIndex, placement, entry)) {
                        continue;
                    }
                    band.ItemList.add(entry);
                }
            }
        }
        return copy;
    }

    /** True when the untouched world config already lists {@code entry} in that pool's target band. */
    private static boolean sourceHasEntry(
            PoolConfigModels.Root source,
            int poolIndex,
            SkinDefinition.LotteryPlacement placement,
            String entry) {
        if (source == null || source.Pools == null || poolIndex < 0 || poolIndex >= source.Pools.size()) {
            return false;
        }
        // Pools are deep-copied one-for-one, so the same index denotes the same pool.
        PoolConfigModels.Pool original = source.Pools.get(poolIndex);
        if (original == null || original.QualityListGroup == null
                || placement.qualityBand() >= original.QualityListGroup.size()) {
            return false;
        }
        PoolConfigModels.QualityBand band = original.QualityListGroup.get(placement.qualityBand());
        return band != null && band.ItemList != null && band.ItemList.contains(entry);
    }

    private static boolean matches(PoolConfigModels.Pool pool, String placementType) {
        if (pool == null || pool.PoolType == null) {
            return false;
        }
        if ("all".equals(placementType)) {
            return "all".equalsIgnoreCase(pool.PoolType.trim());
        }
        return SkinTypeKeys.canonical(pool.PoolType).equals(SkinTypeKeys.canonical(placementType));
    }

    private static PoolConfigModels.Root deepCopy(PoolConfigModels.Root source) {
        PoolConfigModels.Root copy = new PoolConfigModels.Root();
        copy.Pools = new ArrayList<>();
        if (source.Pools == null) {
            return copy;
        }
        for (PoolConfigModels.Pool original : source.Pools) {
            if (original == null) {
                copy.Pools.add(null);
                continue;
            }
            PoolConfigModels.Pool pool = new PoolConfigModels.Pool();
            pool.PoolID = original.PoolID;
            pool.CoverID = original.CoverID;
            pool.Enable = original.Enable;
            pool.PoolName = original.PoolName;
            pool.PoolType = original.PoolType;
            pool.QualityListGroup = new ArrayList<>();
            if (original.QualityListGroup != null) {
                for (PoolConfigModels.QualityBand originalBand : original.QualityListGroup) {
                    if (originalBand == null) {
                        pool.QualityListGroup.add(null);
                        continue;
                    }
                    PoolConfigModels.QualityBand band = new PoolConfigModels.QualityBand();
                    band.Probability = originalBand.Probability;
                    band.ItemList = originalBand.ItemList == null
                            ? new ArrayList<>()
                            : new ArrayList<>(originalBand.ItemList);
                    pool.QualityListGroup.add(band);
                }
            }
            copy.Pools.add(pool);
        }
        return copy;
    }
}
