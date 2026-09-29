package com.habitrain.lottery.client;

import com.habitrain.lottery.client.render.LoginCalendarWorldRender;
import net.fabricmc.api.ClientModInitializer;

public final class HabiLotteryClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SkinClient.register();
        WarehouseHoldKey.register();
        // Skin effects API v1, client half: flight trails for skinned projectiles.
        SkinTrailTicker.register();
        LoginCalendarWorldRender.register();
        LotteryClientNetwork.ensureRegistered();
        CrateClientNetwork.register();
        DailyTaskAdminClient.register();
        DailyShopClient.register();
    }
}
