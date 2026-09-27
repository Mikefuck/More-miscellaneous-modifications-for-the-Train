package com.habitrain.lottery.crate;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrateEmptyDefaultsTest {
    @Test void newlyCreatedBuiltinCratesRequireExplicitRewardsBeforeEnabling() throws Exception {
        var factory = CrateService.class.getDeclaredMethod("defaultPool", CrateCatalog.Entry.class);
        factory.setAccessible(true);
        for (var entry : CrateCatalog.builtins()) {
            var pool = (CrateService.CratePool)factory.invoke(null, entry);
            assertTrue(pool.customPool);
            assertFalse(pool.enabled);
            assertTrue(pool.skinWeights.isEmpty());
            assertTrue(pool.extraRewards.isEmpty());
            assertEquals("unified_pool", pool.rewardMode);
            assertFalse(pool.name.isBlank());
            assertFalse(pool.keyName.isBlank());
        }
    }

    @Test void explicitPoolRetainsMissingProviderEntriesWithoutPopulatingCatalog() throws Exception {
        var pool = new CrateService.CratePool();
        pool.customPool = true;
        pool.skinWeights.put("knife/provider_removed", 80);
        var normalize = CrateService.class.getDeclaredMethod("normalizePool", CrateService.Definition.class, CrateService.CratePool.class);
        normalize.setAccessible(true);
        var entry = CrateCatalog.builtins().getFirst();
        var definition = new CrateService.Definition(entry.id(), entry.nameKey(), entry.keyId(), entry.icon(), entry.color(), entry.primary(), entry.prism());
        normalize.invoke(null, definition, pool);
        assertEquals(java.util.Map.of("knife/provider_removed", 80), pool.skinWeights);
        pool.skinWeights.clear();
        normalize.invoke(null, definition, pool);
        assertTrue(pool.skinWeights.isEmpty());
    }

    @Test void legacyJsonKeepsItsAutomaticPoolAndEnabledSemantics() {
        var legacy = new Gson().fromJson("{}", CrateService.CratePool.class);
        assertFalse(legacy.customPool);
        assertTrue(legacy.enabled);
        assertEquals("skin_plus_bonus", legacy.rewardMode);
    }
}
