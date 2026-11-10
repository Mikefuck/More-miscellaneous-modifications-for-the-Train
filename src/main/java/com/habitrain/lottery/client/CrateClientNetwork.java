package com.habitrain.lottery.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.network.ChunkedJsonTransfer;
import com.habitrain.lottery.network.CrateNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Map;

@Environment(EnvType.CLIENT)
public final class CrateClientNetwork {
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type INVENTORY_TYPE = new TypeToken<Map<String, Double>>() { }.getType();
    private static final ChunkedJsonTransfer.Assembler<String> CATALOG_ASSEMBLER =
            ChunkedJsonTransfer.newClientS2CAssembler();
    private static final ChunkedJsonTransfer.Assembler<String> CONFIG_ASSEMBLER =
            ChunkedJsonTransfer.newClientS2CAssembler();
    private static boolean registered;
    private static CrateNetwork.OpenRequestC2S pendingOpen;
    private static Integer pendingAction;
    private static long actionSentAt;
    private static java.lang.ref.WeakReference<com.habitrain.lottery.client.gui.CrateOpenScreen> pendingScreen =
            new java.lang.ref.WeakReference<>(null);
    public static final CrateClientState STATE = new CrateClientState();

    private CrateClientNetwork() {
    }

    public static void resetAssemblers() {
        CATALOG_ASSEMBLER.reset();
        CONFIG_ASSEMBLER.reset();
    }

    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            resetAssemblers();
            pendingOpen = null;
            pendingAction = null;
            pendingScreen.clear();
            com.habitrain.lottery.crate.CrateCatalog.resetClient();
            STATE.inventory = Map.of();
            STATE.inventoryVersion++;
            STATE.configJson = "{}";
            STATE.configMessage = "";
            STATE.configEditable = false;
            STATE.configVersion++;
            STATE.catalogVersion++;
        }));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(CrateClientNetwork::resetAssemblers));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Resolution retries also run after another screen replaced the animation.
            if (pendingOpen != null && pendingAction != null && System.currentTimeMillis() - actionSentAt >= 1000L) {
                sendResolution();
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.CatalogS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var complete = CATALOG_ASSEMBLER.accept(
                            payload.json(),
                            payload.transferId(),
                            payload.chunkIndex(),
                            payload.chunkCount(),
                            "");
                    if (complete == null) return;
                    try {
                        CrateService.acceptCatalogJson(complete.json());
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
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.PreparedS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var preview = payload.preview();
                    if (pendingOpen == null || !pendingOpen.openId().equals(preview.openId())) return;
                    if (pendingAction != null && pendingAction == CrateNetwork.ResolveOpenC2S.CANCEL) return;
                    if (context.client().screen instanceof com.habitrain.lottery.client.gui.CrateOpenScreen screen)
                        screen.receivePrepared(preview);
                }));
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.OpenResultS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (pendingOpen == null || !pendingOpen.openId().equals(payload.openId())) return;
                    if (!payload.success() && "crates.pending".equals(payload.message())) {
                        var owner = pendingScreen.get();
                        if (owner != null) owner.showPendingSettlement();
                        return;
                    }
                    var owner = pendingScreen.get();
                    pendingOpen = null;
                    pendingAction = null;
                    pendingScreen.clear();
                    if (owner != null) owner.receive(payload);
                    if (payload.success() && Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.WarehouseScreen screen)
                        screen.receiveLateCrateResult(payload.inventoryRevision());
                }));
        ClientPlayNetworking.registerGlobalReceiver(CrateNetwork.ConfigS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    var complete = CONFIG_ASSEMBLER.accept(
                            payload.json(),
                            payload.transferId(),
                            payload.chunkIndex(),
                            payload.chunkCount(),
                            payload.message() == null ? "" : payload.message());
                    if (complete == null) return;
                    STATE.configJson = complete.json() == null ? "{}" : complete.json();
                    STATE.configMessage = complete.metadata() == null ? "" : complete.metadata();
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

    public static boolean canOpen() {
        return ClientPlayNetworking.canSend(CrateNetwork.OpenRequestC2S.TYPE)
                && ClientPlayNetworking.canSend(CrateNetwork.ResolveOpenC2S.TYPE);
    }
    public static boolean canEdit() { return ClientPlayNetworking.canSend(CrateNetwork.ConfigSaveC2S.TYPE); }
    public static boolean hasPendingOpen() { return pendingOpen != null; }
    public static void requestInventory() { if (connected()) ClientPlayNetworking.send(CrateNetwork.InventoryRequestC2S.INSTANCE); }

    public static String open(String crateId, String keyId) {
        if (!canOpen()) return null;
        if (pendingOpen == null) {
            pendingOpen = new CrateNetwork.OpenRequestC2S(crateId, keyId, java.util.UUID.randomUUID().toString());
            pendingAction = null;
            if (Minecraft.getInstance().screen instanceof com.habitrain.lottery.client.gui.CrateOpenScreen screen)
                pendingScreen = new java.lang.ref.WeakReference<>(screen);
        }
        if (!pendingOpen.crateId().equals(crateId) || !pendingOpen.keyId().equals(keyId)) return null;
        ClientPlayNetworking.send(pendingOpen);
        return pendingOpen.openId();
    }

    public static void resolve(String openId, int action) {
        if (pendingOpen == null || !pendingOpen.openId().equals(openId)) return;
        // The first terminal intention wins locally as well as on the server.
        if (pendingAction == null) {
            pendingAction = action;
            sendResolution();
        } else if (System.currentTimeMillis() - actionSentAt >= 1000L) {
            sendResolution();
        }
    }

    private static void sendResolution() {
        if (pendingOpen == null || pendingAction == null
                || !ClientPlayNetworking.canSend(CrateNetwork.ResolveOpenC2S.TYPE)) return;
        ClientPlayNetworking.send(new CrateNetwork.ResolveOpenC2S(pendingOpen.openId(), pendingAction));
        actionSentAt = System.currentTimeMillis();
    }

    public static void requestConfig() { if (connected()) ClientPlayNetworking.send(CrateNetwork.ConfigRequestC2S.INSTANCE); }

    public static void saveConfig(String json) {
        if (!canEdit()) return;
        for (CrateNetwork.ConfigSaveC2S chunk : new CrateNetwork.ConfigSaveC2S(json).toWireChunks()) {
            ClientPlayNetworking.send(chunk);
        }
    }

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
