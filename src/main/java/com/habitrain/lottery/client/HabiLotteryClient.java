package com.habitrain.lottery.client;

import com.habitrain.lottery.client.gui.WarehouseScreen;
import com.habitrain.lottery.client.render.LoginCalendarWorldRender;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class HabiLotteryClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SkinClient.register();
        BackSkinClient.register();
        WarehouseHoldKey.register();
        // Skin effects API v1, client half: flight trails for skinned projectiles.
        SkinTrailTicker.register();
        LoginCalendarWorldRender.register();
        LotteryClientNetwork.ensureRegistered();
        CrateClientNetwork.register();
        DailyTaskAdminClient.register();
        SelfSelectAdminClient.register();
        DailyShopClient.register();
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "warehouse_art_cache");

            @Override
            public ResourceLocation getFabricId() {
                return id;
            }

            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                WarehouseScreen.clearArtCache();
            }
        });
    }
}
