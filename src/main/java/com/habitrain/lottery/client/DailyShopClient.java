package com.habitrain.lottery.client;

import com.habitrain.lottery.network.ChunkedJsonTransfer;
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
    private static final ChunkedJsonTransfer.Assembler<String> CONFIG_ASSEMBLER =
            ChunkedJsonTransfer.newClientS2CAssembler();
    private static boolean registered;

    private DailyShopClient() {
    }

    public static void resetAssembler() {
        CONFIG_ASSEMBLER.reset();
    }

    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(DailyShopNetwork.ConfigS2C.TYPE, (payload, context) ->
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
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.DailyShopManageScreen screen) {
                        screen.onServerUpdate();
                    }
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            resetAssembler();
            json = "{}";
            message = "";
            version++;
        }));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(DailyShopClient::resetAssembler));
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
        try {
            for (DailyShopNetwork.SaveC2S chunk : new DailyShopNetwork.SaveC2S(configJson).toWireChunks()) {
                ClientPlayNetworking.send(chunk);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean buy(String itemId, int price) {
        if (itemId == null || !ClientPlayNetworking.canSend(DailyShopNetwork.BuyC2S.TYPE)) return false;
        ClientPlayNetworking.send(new DailyShopNetwork.BuyC2S(itemId, price));
        return true;
    }
}
