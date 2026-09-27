package com.habitrain.lottery.test;

import com.habitrain.lottery.api.skin.*;
import net.minecraft.resources.ResourceLocation;

/** Test-only provider: actual skin API and installed weapon models, never included in a release. */
public final class CrateFixtureSkins implements SkinRegistrar {
    @Override public void registerSkins() {
        String[] models = {"knife", "revolver", "standard_revolver"};
        for (int i = 0; i < 9; i++) {
            String type = i % 3 == 0 ? "knife" : "revolver";
            HabiSkinApi.register(new SkinDefinition(type, "capture_" + i, 0xFFFFFFFF,
                    ResourceLocation.fromNamespaceAndPath("starrailexpress", "item/" + models[i % 3]),
                    SkinQuality.GOLD));
        }
        HabiSkinApi.register(new SkinDefinition("knife", "quota_blue", 0xFFFFFFFF,
                ResourceLocation.fromNamespaceAndPath("starrailexpress", "item/knife"), SkinQuality.BLUE));
    }
}
