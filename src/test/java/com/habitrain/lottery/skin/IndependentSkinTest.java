package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.*;
import com.habitrain.lottery.config.PoolConfigModels;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IndependentSkinTest {
    @Test void removedCatalogEntriesDisablePoolWithoutChangingSavedConfig() {
        var root = pool("hat", "hat/removed_provider_hat");
        var runtime = SkinPoolInjector.withRegisteredSkins(root);
        assertFalse(runtime.Pools.getFirst().Enable);
        assertEquals(List.of("hat/removed_provider_hat"), root.Pools.getFirst().QualityListGroup.getFirst().ItemList);
        assertTrue(root.Pools.getFirst().Enable);
    }
    @Test void registeredRewardsSurviveAndEmptyBandsKeepIndices() {
        HabiSkinApi.register(SkinDefinition.builder("hat", "independent_test_hat", 0).build());
        var root = pool("hat", "hat/removed_provider_hat");
        var second = new PoolConfigModels.QualityBand(); second.Probability = 0.5;
        second.ItemList.add("hat/independent_test_hat"); root.Pools.getFirst().QualityListGroup.add(second);
        var runtime = SkinPoolInjector.withRegisteredSkins(root);
        assertTrue(runtime.Pools.getFirst().Enable);
        assertEquals(List.of("coin"), runtime.Pools.getFirst().QualityListGroup.getFirst().ItemList);
        assertEquals(List.of("hat/independent_test_hat"), runtime.Pools.getFirst().QualityListGroup.get(1).ItemList);
    }
    @Test void bandBoundariesAndInvalidRewardsFailClosed() {
        assertEquals(0, SkinLotteryRewards.chooseBand(List.of(0.3, 0.7), 0.299999));
        assertEquals(1, SkinLotteryRewards.chooseBand(List.of(0.3, 0.7), 0.3));
        assertEquals(-1, SkinLotteryRewards.chooseBand(List.of(Double.NaN, -1.0), 0.2));
        assertTrue(HabiSkinApi.fromEntry("knife/no_such_skin").isEmpty());
        assertTrue(HabiSkinApi.fromEntry("coin/arbitrary/path").isEmpty());
        assertFalse(SkinLotteryRewards.isCoin("removed_skin"));
    }
    private static PoolConfigModels.Root pool(String type, String entry) {
        var root = new PoolConfigModels.Root(); var pool = new PoolConfigModels.Pool();
        pool.PoolType = type; pool.PoolName = "test";
        var band = new PoolConfigModels.QualityBand(); band.Probability = 0.5; band.ItemList.add(entry);
        pool.QualityListGroup.add(band); root.Pools.add(pool); return root;
    }
}
