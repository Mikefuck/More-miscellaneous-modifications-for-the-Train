package com.habitrain.lottery.crate;

import com.google.gson.Gson;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CratePublicRewardsTest {
    private static final CrateService.Definition WHITE = new CrateService.Definition(
            "woodland", "Wood", "key_woodland", "minecraft:chest", 0, SkinQuality.WHITE, false);

    @Test void customPoolPublishesConfiguredCrossQualitySkinsAndExtrasOnly() {
        HabiSkinApi.register(new SkinDefinition("knife", "public_preview_red", 0,
                ResourceLocation.parse("minecraft:item/iron_sword"), SkinQuality.RED));
        var pool = new CrateService.CratePool();
        pool.customPool = true;
        pool.rewardMode = "unified_pool";
        pool.skinWeights.put("knife/public_preview_red", 10);
        pool.skinWeights.put("knife/missing_provider_preview", 20);
        var apples = new CrateService.ExtraReward(); apples.amount = 80;
        pool.extraRewards.add(apples);
        var disabled = new CrateService.ExtraReward(); disabled.type = "card"; disabled.cardKind = "civilian"; disabled.weight = 0;
        pool.extraRewards.add(disabled);
        assertEquals(List.of(new CrateCatalog.RewardPreview("skin", "knife/public_preview_red", 1, "red"),
                new CrateCatalog.RewardPreview("green_apples", "green_apples", 80, "white")), CrateService.publicRewards(WHITE, pool));
        pool.skinWeights.put("knife/public_preview_red", 0);
        assertEquals(1, CrateService.publicRewards(WHITE, pool).size());
    }

    @Test void actualDrawModeControlsCandidateVisibility() {
        var pool = new CrateService.CratePool(); pool.customPool = true;
        var reward = new CrateService.ExtraReward(); reward.chance = 0; reward.weight = 100;
        pool.extraRewards.add(reward);
        assertTrue(CrateService.publicRewards(WHITE, pool).isEmpty());
        pool.rewardMode = "unified_pool";
        assertEquals(1, CrateService.publicRewards(WHITE, pool).size());
        pool.minimumSkinCount = pool.rollCount;
        assertTrue(CrateService.publicRewards(WHITE, pool).isEmpty());
        pool.rewardMode = "skin_plus_bonus"; reward.chance = .5; reward.weight = 0;
        assertEquals(1, CrateService.publicRewards(WHITE, pool).size());
    }

    @Test void oldCatalogDoesNotInventPreviewRewards() {
        var gson = new Gson();
        var json = gson.toJsonTree(CrateCatalog.builtins().getFirst()).getAsJsonObject();
        json.remove("rewards");
        assertTrue(gson.fromJson(json, CrateCatalog.Entry.class).rewards().isEmpty());
    }
}
