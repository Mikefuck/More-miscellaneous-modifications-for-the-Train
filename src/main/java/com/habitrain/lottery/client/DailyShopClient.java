package com.habitrain.lottery.client;

import com.habitrain.lottery.network.DailyShopNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Client end of the daily shop: purchases, and the editor channel the Mod Menu screen polls. */
@Environment(EnvType.CLIENT)
public final class DailyShopClient {
    public static volatile String json = "{}";
    public static volatile String message = "";
    public static volatile int version;
    private static boolean registered;

    private DailyShopClient() { }

    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(DailyShopNetwork.ConfigS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    json = payload.json() == null ? "{}" : payload.json();
                    message = payload.message() == null ? "" : payload.message();
                    version++;
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.DailyShopManageScreen screen) {
                        screen.onServerUpdate();
                    }
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            json = "{}";
            message = "";
            version++;
        }));
    }

    public static boolean connected() {
        return ClientPlayNetworking.canSend(DailyShopNetwork.RequestC2S.TYPE);
    }

    public static boolean request() {
        if (!connected()) return false;
        ClientPlayNetworking.send(DailyShopNetwork.RequestC2S.INSTANCE);
        return true;
    }

    public static boolean save(String configJson) {
        if (!ClientPlayNetworking.canSend(DailyShopNetwork.SaveC2S.TYPE)) return false;
        ClientPlayNetworking.send(new DailyShopNetwork.SaveC2S(configJson));
        return true;
    }

    public static boolean buy(String itemId, int price) {
        if (itemId == null || !ClientPlayNetworking.canSend(DailyShopNetwork.BuyC2S.TYPE)) return false;
        ClientPlayNetworking.send(new DailyShopNetwork.BuyC2S(itemId, price));
        return true;
    }
}
