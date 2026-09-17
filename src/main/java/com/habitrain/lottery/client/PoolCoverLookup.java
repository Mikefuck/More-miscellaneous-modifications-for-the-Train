package com.habitrain.lottery.client;

import com.habitrain.lottery.config.PoolConfigModels;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps current SRE {@code PoolID} → stable cover art index ({@code CoverID}).
 * SRE's LootInfoScreen binds sketch textures to PoolID; after we reorder by
 * swapping PoolIDs, that would pin covers to slots. This table keeps art with
 * the pool content instead.
 */
@Environment(EnvType.CLIENT)
public final class PoolCoverLookup {
    private static final Map<Integer, Integer> POOL_ID_TO_COVER = new HashMap<>();

    private PoolCoverLookup() {
    }

    public static void rebuild(PoolConfigModels.Root root) {
        POOL_ID_TO_COVER.clear();
        if (root == null || root.Pools == null) {
            return;
        }
        for (PoolConfigModels.Pool pool : root.Pools) {
            if (pool == null) {
                continue;
            }
            POOL_ID_TO_COVER.put(pool.PoolID, pool.resolvedCoverId());
        }
    }

    public static int coverIdForPoolId(int poolId) {
        Integer cover = POOL_ID_TO_COVER.get(poolId);
        return cover != null ? cover : poolId;
    }

    public static ResourceLocation sketchTexture(int poolId) {
        int coverId = coverIdForPoolId(poolId);
        return ResourceLocation.fromNamespaceAndPath(
                "noellesroles", "textures/gui/loot/pool_bg" + coverId + ".png");
    }
}
