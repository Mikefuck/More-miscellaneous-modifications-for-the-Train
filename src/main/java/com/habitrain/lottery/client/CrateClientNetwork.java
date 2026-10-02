package com.habitrain.lottery.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.habitrain.lottery.network.CrateNetwork;
import com.habitrain.lottery.crate.CrateService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.Map;

@Environment(EnvType.CLIENT)
public final class CrateClientNetwork {
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type INVENTORY_TYPE = new TypeToken<Map<String, Double>>() { }.getType();
    private static boolean registered;
    private static CrateNetwork.OpenRequestC2S pendingOpen;
    public static final CrateClientState STATE = new CrateClientState();
    private CrateClientNetwork() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            com.habitrain.lottery.crate.CrateCatalog.reset();
            STATE.inventory = Map.of();
            STATE.inventoryVersion++;
            STATE.configJson = "{}";
            STATE.configMessage = "";
            STATE.configEditable = false;
            STATE.configVersion++;
            STATE.catalogVersion++;
        }));
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.CatalogS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try {
                        CrateService.acceptCatalogJson(payload.json());
                        STATE.catalogVersion++;
                        if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateAdminScreen screen)
                            screen.refreshCatalog();
                    } catch (RuntimeException ignored) { }
                }));
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.InventoryS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try { STATE.inventory = GSON.fromJson(payload.json(), INVENTORY_TYPE); }
                    catch (RuntimeException ignored) { STATE.inventory = Map.of(); }
                    STATE.inventoryVersion++;
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateOpenScreen screen) screen.refreshFromState();
                }));
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.OpenResultS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (pendingOpen == null || !pendingOpen.openId().equals(payload.openId())) return;
                    if (payload.success() || !"crates.pending".equals(payload.message())) pendingOpen = null;
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateOpenScreen screen) screen.receive(payload);
                    else if (payload.success() && Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.WarehouseScreen screen)
                        screen.receiveLateCrateResult(payload.inventoryRevision());
                }));
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.ConfigS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    STATE.configJson = payload.json() == null ? "{}" : payload.json();
                    STATE.configMessage = payload.message() == null ? "" : payload.message();
                    STATE.configEditable = !"crates.no_permission".equals(STATE.configMessage)
                            && !"crates.client_outdated".equals(STATE.configMessage)
                            && !"{}".equals(STATE.configJson);
                    STATE.configVersion++;
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateAdminScreen screen) screen.refreshFromState();
                    if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateManageScreen screen) screen.refreshFromState();
                }));
    }

    public static boolean connected() {
        return ClientPlayNetworking.canSend(CrateNetwork.InventoryRequestC2S.TYPE);
    }
    public static boolean canOpen() { return ClientPlayNetworking.canSend(CrateNetwork.OpenRequestC2S.TYPE); }
    public static boolean canEdit() { return ClientPlayNetworking.canSend(CrateNetwork.ConfigSaveC2S.TYPE); }
    public static void requestInventory() { if (connected()) ClientPlayNetworking.send(CrateNetwork.InventoryRequestC2S.INSTANCE); }
    public static String open(String crateId, String keyId) {
        if (!canOpen()) return null;
        if (pendingOpen == null) pendingOpen = new CrateNetwork.OpenRequestC2S(
                crateId, keyId, java.util.UUID.randomUUID().toString());
        if (!pendingOpen.crateId().equals(crateId) || !pendingOpen.keyId().equals(keyId)) return null;
        ClientPlayNetworking.send(pendingOpen);
        return pendingOpen.openId();
    }
    public static void requestConfig() { if (connected()) ClientPlayNetworking.send(CrateNetwork.ConfigRequestC2S.INSTANCE); }
    public static void saveConfig(String json) { if (canEdit()) ClientPlayNetworking.send(new CrateNetwork.ConfigSaveC2S(json)); }

    public static final class CrateClientState {
        public Map<String, Double> inventory = Map.of();
        public int inventoryVersion;
        public String configJson = "{}";
        public String configMessage = "";
        public boolean configEditable;
        public int configVersion;
        public int catalogVersion;
    }
}
