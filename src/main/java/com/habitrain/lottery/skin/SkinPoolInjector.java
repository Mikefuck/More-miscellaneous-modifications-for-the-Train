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
        return copyWithSkins(source, HabiSkinApi.registrations());
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
                for (PoolConfigModels.Pool pool : copy.Pools) {
                    if (!matches(pool, placement.poolType()) || contains(pool, entry)) {
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
                    band.ItemList.add(entry);
                }
            }
        }
        return copy;
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

    private static boolean contains(PoolConfigModels.Pool pool, String entry) {
        if (pool == null || pool.QualityListGroup == null) {
            return false;
        }
        for (PoolConfigModels.QualityBand band : pool.QualityListGroup) {
            if (band != null && band.ItemList != null && band.ItemList.contains(entry)) {
                return true;
            }
        }
        return false;
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
