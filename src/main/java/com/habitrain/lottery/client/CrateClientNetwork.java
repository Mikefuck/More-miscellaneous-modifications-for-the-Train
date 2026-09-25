package com.habitrain.lottery.client;

import com.google.gson.Gson;
import com.habitrain.lottery.network.CrateNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.Map;

@Environment(EnvType.CLIENT)
public final class CrateClientNetwork {
    private static final Gson GSON = new Gson();
    private static boolean registered;
    public static final CrateClientState STATE = new CrateClientState();
    private CrateClientNetwork() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.InventoryS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try { STATE.inventory = GSON.fromJson(payload.json(), Map.class); }
                    catch (RuntimeException ignored) { STATE.inventory = Map.of(); }
                    STATE.inventoryVersion++;
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateOpenScreen screen) screen.refreshFromState();
                }));
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.OpenResultS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateOpenScreen screen) screen.receive(payload);
                }));
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.ConfigS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    STATE.configJson = payload.json() == null ? "{}" : payload.json();
                    STATE.configMessage = payload.message() == null ? "" : payload.message();
                    STATE.configVersion++;
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateAdminScreen screen) screen.refreshFromState();
                }));
    }

    public static boolean connected() {
        return ClientPlayNetworking.canSend(CrateNetwork.InventoryRequestC2S.TYPE);
    }
    public static void requestInventory() { if (connected()) ClientPlayNetworking.send(CrateNetwork.InventoryRequestC2S.INSTANCE); }
    public static void open(String crateId, String keyId) { if (connected()) ClientPlayNetworking.send(new CrateNetwork.OpenRequestC2S(crateId, keyId)); }
    public static void requestConfig() { if (connected()) ClientPlayNetworking.send(CrateNetwork.ConfigRequestC2S.INSTANCE); }
    public static void saveConfig(String json) { if (connected()) ClientPlayNetworking.send(new CrateNetwork.ConfigSaveC2S(json)); }

    public static final class CrateClientState {
        public Map<String, Double> inventory = Map.of();
        public int inventoryVersion;
        public String configJson = "{}";
        public String configMessage = "";
        public int configVersion;
    }
}
