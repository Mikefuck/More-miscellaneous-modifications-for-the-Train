package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.config.PoolConfigModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkinPoolInjectorTest {

    @Test
    void injectsTypeAndAllPoolsWithoutMutatingWorldConfig() {
        PoolConfigModels.Root source = root(pool("knife", "knife/original"), pool("all", "bat/original"));
        SkinDefinition definition = SkinDefinition.builder("knife", "api_blade", 0)
                .model("example", "item/skins/knife/api_blade")
                .includeInDefaultPools()
                .build();

        PoolConfigModels.Root effective = SkinPoolInjector.copyWithSkins(source, List.of(definition));

        assertEquals(List.of("knife/original"), source.Pools.get(0).QualityListGroup.get(0).ItemList);
        assertEquals(List.of("knife/original", "knife/api_blade"),
                effective.Pools.get(0).QualityListGroup.get(0).ItemList);
        assertEquals(List.of("bat/original", "knife/api_blade"),
                effective.Pools.get(1).QualityListGroup.get(0).ItemList);
    }

    @Test
    void mapsRevolverToGunAndDoesNotDuplicateExistingEntries() {
        PoolConfigModels.Root source = root(pool("gun", "gun/api_revolver"), pool("all", "coin"));
        SkinDefinition definition = SkinDefinition.builder("revolver", "api_revolver", 0)
                .includeInDefaultPools()
                .build();

        PoolConfigModels.Root effective = SkinPoolInjector.copyWithSkins(source, List.of(definition, definition));

        assertEquals(List.of("gun/api_revolver"), effective.Pools.get(0).QualityListGroup.get(0).ItemList);
        assertEquals(List.of("coin", "gun/api_revolver"), effective.Pools.get(1).QualityListGroup.get(0).ItemList);
    }

    @Test
    void skipsPlacementWhenConfiguredBandDoesNotExist() {
        PoolConfigModels.Root source = root(pool("bat", "bat/original"));
        SkinDefinition definition = SkinDefinition.builder("bat", "api_bat", 0)
                .addToPool("bat", 4)
                .build();

        PoolConfigModels.Root effective = SkinPoolInjector.copyWithSkins(source, List.of(definition));

        assertEquals(List.of("bat/original"), effective.Pools.get(0).QualityListGroup.get(0).ItemList);
    }

    private static PoolConfigModels.Root root(PoolConfigModels.Pool... pools) {
        PoolConfigModels.Root root = new PoolConfigModels.Root();
        root.Pools.addAll(List.of(pools));
        return root;
    }

    private static PoolConfigModels.Pool pool(String type, String... items) {
        PoolConfigModels.Pool pool = new PoolConfigModels.Pool();
        pool.PoolName = type;
        pool.PoolType = type;
        PoolConfigModels.QualityBand band = new PoolConfigModels.QualityBand();
        band.Probability = 1.0;
        band.ItemList.addAll(List.of(items));
        pool.QualityListGroup.add(band);
        return pool;
    }
}
