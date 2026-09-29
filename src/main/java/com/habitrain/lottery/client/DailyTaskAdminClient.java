package com.habitrain.lottery.client;

import com.habitrain.lottery.network.DailyTaskAdminNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Client end of the daily-task editor channel. The screen polls {@link #version}. */
@Environment(EnvType.CLIENT)
public final class DailyTaskAdminClient {
    public static volatile String json = "{}";
    public static volatile String message = "";
    public static volatile int version;
    private static boolean registered;

    private DailyTaskAdminClient() { }

    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(DailyTaskAdminNetwork.ConfigS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    json = payload.json() == null ? "{}" : payload.json();
                    message = payload.message() == null ? "" : payload.message();
                    version++;
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.DailyTaskManageScreen screen) {
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
        return ClientPlayNetworking.canSend(DailyTaskAdminNetwork.RequestC2S.TYPE);
    }

    public static boolean request() {
        if (!connected()) return false;
        ClientPlayNetworking.send(DailyTaskAdminNetwork.RequestC2S.INSTANCE);
        return true;
    }

    public static boolean save(String configJson) {
        if (!ClientPlayNetworking.canSend(DailyTaskAdminNetwork.SaveC2S.TYPE)) return false;
        ClientPlayNetworking.send(new DailyTaskAdminNetwork.SaveC2S(configJson));
        return true;
    }
}
