package com.habitrain.lottery.test;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.*;
import com.habitrain.lottery.client.SkinClient;
import com.habitrain.lottery.client.gui.SkinWardrobeScreen;
import com.habitrain.lottery.client.gui.WarehouseScreen;
import com.habitrain.lottery.network.WarehouseNetwork;
import com.habitrain.lottery.skin.SkinNetwork;
import com.habitrain.lottery.warehouse.WarehouseEntry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.util.*;

/** Test-only fixtures. No world, rewards or player saves are changed. */
public final class QualitySmoke implements ClientModInitializer, SkinRegistrar {
    private int ticks;
    private WarehouseScreen warehouse;
    private SkinWardrobeScreen wardrobe;

    @Override public void registerSkins() {
        for (var q : SkinQuality.values()) {
            // Deliberately WHITE locally: the snapshot must control both screens' colors.
            HabiSkinApi.register(SkinDefinition.builder("knife", "quality_" + q.ordinal(), SkinQuality.WHITE)
                    .model("minecraft", "item/diamond").build());
        }
    }

    private static Object get(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private static void set(Object o, String name, Object value) throws Exception {
        var f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, value);
    }
    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message ->
                HabiLotteryMod.LOGGER.info("QUALITY_CAPTURE {} {}", name, message.getString()));
    }
    private static void size(Minecraft mc, int w, int h, int scale) {
        mc.options.guiScale().set(scale);
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), w, h);
        mc.resizeDisplay();
    }
    private void warehouse(Minecraft mc) throws Exception {
        warehouse = new WarehouseScreen(null); mc.setScreen(warehouse);
        var rows = new ArrayList<WarehouseEntry>();
        for (var q : SkinQuality.values()) rows.add(new WarehouseEntry("skin", "knife/quality_" + q.ordinal(),
                "品质展示 · " + net.minecraft.network.chat.Component.translatable(q.translationKey()).getString(),
                "服务端指定品质，客户端本地注册为白色。", "minecraft:diamond", 1, 0xFF00FF00, q == SkinQuality.RED, q));
        var skins = HabiSkinApi.registrations().stream()
                .filter(d -> "habitrain_skins".equals(d.model().getNamespace())).toList();
        if (skins.size() != 7 || skins.stream().anyMatch(d -> d.quality() != SkinQuality.RED))
            throw new AssertionError("External provider did not register seven RED skins");
        for (var d : skins) rows.add(new WarehouseEntry("skin", d.type() + "/" + d.id(),
                "skin.habitrain_lottery." + d.type() + "." + d.id(), "全部七款皮肤均为红色品质。",
                "minecraft:diamond", 1, d.color(), false, d.quality()));
        set(warehouse, "loading", true); set(warehouse, "requestId", 901); set(warehouse, "expectedTotal", -1);
        warehouse.receive(new WarehouseNetwork.Snapshot(901, 0, rows.size(), "", rows));
        set(warehouse, "cardsKnown", true); set(warehouse, "error", "");
    }
    private void wardrobe(Minecraft mc) throws Exception {
        var entries = Arrays.stream(SkinQuality.values()).map(q ->
                new SkinNetwork.Entry("knife", "quality_" + q.ordinal(), q != SkinQuality.BLUE, q == SkinQuality.RED, q)).toList();
        var field = SkinClient.class.getDeclaredField("entries"); field.setAccessible(true); field.set(null, entries);
        wardrobe = new SkinWardrobeScreen(null);
        set(wardrobe, "initialized", true); set(wardrobe, "received", true); set(wardrobe, "view3d", false);
        set(wardrobe, "selected", "quality_4");
        mc.setScreen(wardrobe); wardrobe.refresh();
    }
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getOverlay() != null || mc.screen == null) return;
            try {
                ++ticks;
                if (ticks == 30) {
                    mc.options.languageCode = "zh_cn"; mc.getLanguageManager().setSelected("zh_cn"); mc.reloadResourcePacks();
                }
                if (ticks == 70) { size(mc, 1440, 900, 2); warehouse(mc); }
                if (ticks == 95) shot(mc, "quality-warehouse-wide");
                if (ticks == 96) {
                    var tiles = (List<?>) get(warehouse, "tiles");
                    Object red = tiles.stream().filter(t -> {
                        try { return ((WarehouseEntry) get(t, "entry")).id().equals("knife/quality_4"); }
                        catch (Exception ex) { throw new RuntimeException(ex); }
                    }).findFirst().orElseThrow();
                    var m = warehouse.getClass().getDeclaredMethod("openDetail", red.getClass()); m.setAccessible(true); m.invoke(warehouse, red);
                }
                if (ticks == 115) shot(mc, "quality-warehouse-detail");
                if (ticks == 116) wardrobe(mc);
                if (ticks == 135) shot(mc, "quality-wardrobe-wide");
                if (ticks == 136) { size(mc, 960, 720, 3); wardrobe(mc); }
                if (ticks == 155) shot(mc, "quality-wardrobe-small");
                if (ticks == 156) warehouse(mc);
                if (ticks == 175) shot(mc, "quality-warehouse-small");
                if (ticks == 185) {
                    HabiLotteryMod.LOGGER.info("QUALITY_SMOKE_OK: five server qualities, seven RED external skins, wide/compact wardrobe and warehouse");
                    mc.stop();
                }
            } catch (Exception e) { throw new RuntimeException("Quality GUI smoke failed", e); }
        });
    }
}
