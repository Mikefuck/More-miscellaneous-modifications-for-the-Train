package com.habitrain.lottery.test;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiSkinPlayerApi;
import com.habitrain.lottery.api.skin.*;
import com.habitrain.lottery.api.skin.client.SkinPreviewContext;
import com.habitrain.lottery.client.BackSkinClient;
import com.habitrain.lottery.client.gui.SkinWardrobeScreen;
import com.habitrain.lottery.client.render.BackSkinLayer;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Uses actual UI clicks and client/server packets in a fresh disposable integrated world. */
public final class BackSmoke implements ClientModInitializer, SkinRegistrar {
    private static final String PACK = "smoke_pack", WINGS = "smoke_wings";
    private final List<String> checks = new ArrayList<>();
    private final UUID observerOwner = UUID.randomUUID();
    private int phase, ticks, delay;
    private SkinWardrobeScreen screen;
    private CompletableFuture<Void> work;
    private net.minecraft.server.level.ServerPlayer remote;

    @Override public void registerSkins() {
        HabiSkinApi.register(SkinDefinition.builder("back", PACK, SkinQuality.PURPLE)
                .model("back_smoke", "item/pack").build());
        HabiSkinApi.register(SkinDefinition.builder("back", WINGS, SkinQuality.RED)
                .model("minecraft", "block/diamond_block").build());
        HabiSkinApi.register(SkinDefinition.builder("back", "smoke_missing", 0)
                .model("back_smoke", "item/missing").build());
    }

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getOverlay() != null || phase < 0) return;
            try { tick(mc); }
            catch (Throwable error) {
                HabiLotteryMod.LOGGER.error("BACK_SMOKE FAIL phase {}", phase, error);
                checks.add("FAIL " + error);
                finish(mc);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        if (++ticks > 9000) throw new AssertionError("timeout phase " + phase);
        if (delay > 0) { delay--; return; }
        if (work != null) {
            if (!work.isDone()) return;
            work.join(); work = null;
        }
        switch (phase) {
            case 0 -> {
                if (ticks < 40 || mc.screen == null) return;
                mc.options.languageCode = "zh_cn";
                mc.getLanguageManager().setSelected("zh_cn"); mc.reloadResourcePacks();
                mc.options.renderDistance().set(2);
                size(mc, 1280, 800, 2);
                next(60);
            }
            case 1 -> {
                String world = "back-smoke-" + UUID.randomUUID();
                mc.createWorldOpenFlows().createFreshLevel(world,
                        new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.PEACEFUL, false,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42L, false, false),
                        r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                                .createWorldDimensions(), new TitleScreen());
                next(0);
            }
            case 2 -> {
                if (mc.player == null || mc.getSingleplayerServer() == null) return;
                work = server(mc, () -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    check(HabiSkinPlayerApi.unlock(player, "back", PACK).ok(), "unlock-back-api");
                    check(HabiSkinPlayerApi.equip(player, "back", PACK).ok(), "equip-back-api");
                    player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                });
                next(30);
            }
            case 3 -> {
                if (!appearance(mc, PACK)) return;
                check(mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), "chest-slot-independent");
                var layers = LivingEntityRenderer.class.getDeclaredField("layers"); layers.setAccessible(true);
                check(((List<?>) layers.get(mc.getEntityRenderDispatcher().getRenderer(mc.player)))
                        .stream().anyMatch(BackSkinLayer.class::isInstance), "real-player-layer-installed");
                screen = new SkinWardrobeScreen(null); mc.setScreen(screen);
                mc.getToasts().clear();
                click("背部");
                next(20);
            }
            case 4 -> {
                shot(mc, "01-back-equipped");
                clickPrefix("测试背饰 ·");
                check(!equip().active, "locked-back-cannot-equip");
                check(appearance(mc, PACK), "browsing-does-not-change-world-appearance");
                var session = new SkinPreviewContext.Session();
                session.render(mc.player, "back/" + WINGS, () -> check(appearance(mc, WINGS), "unowned-preview-override"));
                session.render(mc.player, "back/default", () -> check(BackSkinClient.appearanceFor(mc.player).isEmpty(), "default-preview-hides-equipped-back"));
                check(appearance(mc, PACK), "preview-restores-world-appearance");
                next(10);
            }
            case 5 -> {
                shot(mc, "02-back-locked-preview");
                clickPrefix("缺失模型测试 ·");
                check(!equip().active, "missing-base-model-cannot-equip");
                work = server(mc, () -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    check(HabiSkinPlayerApi.unlock(player, "back", WINGS).ok(), "unlock-second-back");
                });
                next(20);
            }
            case 6 -> {
                clickPrefix("测试背饰 ·");
                check(equip().active, "owned-back-equip-button-enabled");
                equip().onPress(); next(20);
            }
            case 7 -> {
                if (!appearance(mc, WINGS)) return;
                check(true, "wardrobe-switch-updates-real-packet-cache");
                shot(mc, "03-back-switched");
                clickPrefix("默认外观 ·");
                check(equip().active, "restore-default-button-enabled");
                equip().onPress(); next(20);
            }
            case 8 -> {
                if (BackSkinClient.appearanceFor(mc.player).isPresent()) return;
                check(true, "restore-clears-real-back");
                shot(mc, "04-default-preview");
                work = server(mc, () -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    check("default".equals(HabiSkinPlayerApi.equipped(player.getUUID(), "back")), "restore-persisted");
                    check(HabiSkinPlayerApi.equip(player, "back", PACK).ok(), "re-equip-before-revoke");
                    check(HabiSkinPlayerApi.lock(player, "back", PACK).ok(), "revoke-equipped-back");
                    remote = FakePlayer.get(player.serverLevel(), new GameProfile(observerOwner, "BackObserverFixture"));
                    check(HabiSkinPlayerApi.unlock(remote.getUUID(), "back", WINGS).ok(), "observer-owner-unlocked");
                    check(HabiSkinPlayerApi.equip(remote.getUUID(), "back", WINGS).ok(), "observer-owner-equipped");
                    EntityTrackingEvents.START_TRACKING.invoker().onStartTracking(remote, player);
                });
                next(20);
            }
            case 9 -> {
                check(BackSkinClient.appearanceFor(mc.player).isEmpty(), "revoke-clears-synchronized-back");
                var remoteClient = new RemotePlayer(mc.level, new GameProfile(observerOwner, "BackObserverFixture"));
                check(BackSkinClient.appearanceFor(remoteClient).map(SkinDefinition::id).orElse("").equals(WINGS), "observer-tracking-packet-delivered");
                work = server(mc, () -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    EntityTrackingEvents.STOP_TRACKING.invoker().onStopTracking(remote, player);
                    check(player.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), "switching-keeps-server-chest-equipment");
                });
                size(mc, 960, 720, 3); next(20);
            }
            case 10 -> {
                var remoteClient = new RemotePlayer(mc.level, new GameProfile(observerOwner, "BackObserverFixture"));
                check(BackSkinClient.appearanceFor(remoteClient).isEmpty(), "stop-tracking-clears-observer-cache");
                click("背部"); click(">"); clickPrefix("测试背饰 ·");
                check(screen.width == 320 && equip().active, "back-button-and-equip-at-minimum-gui-size");
                next(10);
            }
            case 11 -> { shot(mc, "05-back-small"); finish(mc); }
        }
    }

    private CompletableFuture<Void> server(Minecraft mc, Runnable operation) {
        var result = new CompletableFuture<Void>();
        mc.getSingleplayerServer().execute(() -> {
            try { operation.run(); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
    private static boolean appearance(Minecraft mc, String id) {
        return BackSkinClient.appearanceFor(mc.player).map(SkinDefinition::id).orElse("").equals(id);
    }
    private Button equip() throws Exception {
        var field = SkinWardrobeScreen.class.getDeclaredField("equip"); field.setAccessible(true);
        return (Button) field.get(screen);
    }
    private void click(String label) { clickMatch(label, false); }
    private void clickPrefix(String label) { clickMatch(label, true); }
    private void clickMatch(String label, boolean prefix) {
        Button button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.visible && (prefix ? b.getMessage().getString().startsWith(label) : b.getMessage().getString().equals(label)))
                .findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
        check(button.active, "clickable:" + label);
        screen.mouseClicked(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0);
    }
    private void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        synchronized (checks) { checks.add("PASS " + label); }
    }
    private void next(int delay) { phase++; this.delay = delay; }
    private static void size(Minecraft mc, int width, int height, int scale) {
        mc.options.guiScale().set(scale);
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), width, height);
        mc.resizeDisplay();
    }
    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message ->
                HabiLotteryMod.LOGGER.info("BACK_CAPTURE {} {}", name, message.getString()));
    }
    private void finish(Minecraft mc) {
        phase = -1;
        try { Files.write(mc.gameDirectory.toPath().resolve("back-smoke-results.txt"), checks); }
        catch (Exception error) { throw new RuntimeException(error); }
        HabiLotteryMod.LOGGER.info("BACK_SMOKE_DONE {}", checks);
        mc.stop();
    }
}
