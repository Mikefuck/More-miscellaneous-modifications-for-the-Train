package com.habitrain.lottery.client;

import com.habitrain.lottery.network.ChunkedJsonTransfer;
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
    private static final ChunkedJsonTransfer.Assembler<String> CONFIG_ASSEMBLER =
            ChunkedJsonTransfer.newClientS2CAssembler();
    private static boolean registered;

    private DailyTaskAdminClient() {
    }

    public static void resetAssembler() {
        CONFIG_ASSEMBLER.reset();
    }

    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(DailyTaskAdminNetwork.ConfigS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var complete = CONFIG_ASSEMBLER.accept(
                            payload.json(),
                            payload.transferId(),
                            payload.chunkIndex(),
                            payload.chunkCount(),
                            payload.message() == null ? "" : payload.message());
                    if (complete == null) return;
                    json = complete.json() == null ? "{}" : complete.json();
                    message = complete.metadata() == null ? "" : complete.metadata();
                    version++;
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.DailyTaskManageScreen screen) {
                        screen.onServerUpdate();
                    }
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            resetAssembler();
            json = "{}";
            message = "";
            version++;
        }));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(DailyTaskAdminClient::resetAssembler));
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
        try {
            for (DailyTaskAdminNetwork.SaveC2S chunk : new DailyTaskAdminNetwork.SaveC2S(configJson).toWireChunks()) {
                ClientPlayNetworking.send(chunk);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
