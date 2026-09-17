package com.habitrain.lottery.config;

import java.util.ArrayList;
import java.util.List;

/** Gson models compatible with SRE LotteryPoolsConfig field names. */
public final class PoolConfigModels {
    private PoolConfigModels() {
    }

    public static final class Root {
        public List<Pool> Pools = new ArrayList<>();
    }

    public static final class Pool {
        public int PoolID;
        /**
         * SRE LootInfoScreen sketch art index ({@code pool_bg{N}.png}).
         * Kept independent of {@link #PoolID} so reordering (which swaps PoolIDs
         * for sidebar sort) does not rebind a pool's cover to its new slot.
         * {@code null} means "not yet set" — resolve to {@link #PoolID}.
         */
        public Integer CoverID;
        public boolean Enable = true;
        public String PoolName = "";
        public String PoolType = "";
        public List<QualityBand> QualityListGroup = new ArrayList<>();

        /** Cover art index used by the client sketch texture. */
        public int resolvedCoverId() {
            return CoverID != null ? CoverID : PoolID;
        }
    }

    public static final class QualityBand {
        public Double Probability;
        public List<String> ItemList = new ArrayList<>();
    }

    /**
     * Pin missing cover indices to the pool's current {@link Pool#PoolID}.
     * Call before any reorder that swaps PoolIDs so art stays with pool content.
     */
    public static void ensureCoverIds(Root root) {
        if (root == null || root.Pools == null) {
            return;
        }
        for (Pool pool : root.Pools) {
            if (pool != null && pool.CoverID == null) {
                pool.CoverID = pool.PoolID;
            }
        }
    }
}
