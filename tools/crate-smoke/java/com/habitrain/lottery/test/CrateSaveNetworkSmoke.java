package com.habitrain.lottery.test;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.gui.CrateManageScreen;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real integrated-server packets, a no-cheats owner, actual Save clicks, and service reload from disk. */
final class CrateSaveNetworkSmoke {
    private static final Gson GSON = new Gson();
    private static final String UI = "screen.habitrain_lottery.reward_editor.";
    private static final String MANAGE = "screen.habitrain_lottery.crate_manage.";
    private final String world = "crate-save-" + UUID.randomUUID();
    private final List<String> checks = new ArrayList<>();
    private int phase, ticks, waitTicks;
    private CrateManageScreen screen;
    private CompletableFuture<Boolean> serverWork;
    private Path output;

    void tick(Minecraft mc) throws Exception {
        if (++ticks > 6000) throw new IllegalStateException("Network smoke timed out at phase " + phase);
        if (mc.getOverlay() != null) return;
        switch (phase) {
            case 0 -> {
                if (ticks < 30) return;
                output = mc.gameDirectory.toPath().resolve("save-network-1.1.39"); Files.createDirectories(output);
                mc.options.renderDistance().set(2); mc.options.simulationDistance().set(5);
                mc.options.guiScale().set(2);
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1280, 720); mc.resizeDisplay();
                phase++;
                mc.createWorldOpenFlows().createFreshLevel(world,
                        new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.PEACEFUL, false,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42L, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),
                        new TitleScreen());
            }
            case 1 -> {
                if (mc.player == null || !CrateClientNetwork.connected()) return;
                check(!LotteryNetwork.ClientLotteryState.op, "no-cheats-owner-is-not-op");
                serverWork = mc.getSingleplayerServer().submit(() -> {
                    var state = GSON.fromJson(CrateService.configJson(), CrateService.State.class);
                    var pool = state.crates.get("woodland"); pool.enabled = true;
                    pool.skinWeights.put("knife/capture_0", 100);
                    var item = new CrateService.ExtraReward(); item.weight = 10; pool.extraRewards.add(item);
                    return CrateService.applyConfigJson(GSON.toJson(state));
                });
                phase++;
            }
            case 2 -> {
                if (!serverWork.isDone()) return;
                check(serverWork.join(), "fixture-persisted");
                screen = new CrateManageScreen(new TitleScreen()); mc.setScreen(screen); phase++;
            }
            case 3 -> {
                if (!CrateClientNetwork.STATE.configEditable || get(screen, "draft") == null) return;
                check(!LotteryNetwork.ClientLotteryState.op, "crate-authorization-independent-of-op-cache");
                click(MANAGE + "rewards"); edit(UI + "weight", "10");
                check((boolean)get(screen, "dirty"), "editing-marks-draft-dirty");
                click(UI + "only_reward");
                check(((Button)get(screen, "saveButton")).active, "actual-save-button-enabled-for-owner");
                click(MANAGE + "save"); phase++;
            }
            case 4 -> {
                if ((boolean)get(screen, "pending")) return;
                check("crates.config_saved".equals(CrateClientNetwork.STATE.configMessage), "server-save-acknowledged");
                check(!(boolean)get(screen, "dirty"), "ack-clears-dirty");
                serverWork = mc.getSingleplayerServer().submit(() -> {
                    try {
                        var saved = GSON.fromJson(Files.readString(WorldLotteryPaths.configFile("crates.json")), CrateService.State.class);
                        var p = saved.crates.get("woodland");
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                        HabiSystemItemApi.grant(player.getUUID(), ResourceLocation.parse(CrateService.crateItemId("woodland")), 1);
                        HabiSystemItemApi.grant(player.getUUID(), ResourceLocation.parse(CrateService.keyItemId("woodland")), 1);
                        var result = CrateService.open(player, "woodland", CrateService.keyItemId("woodland"));
                        return p.skinWeights.get("knife/capture_0") == 10 && p.extraRewards.getFirst().weight == 0
                                && p.rollCount == 1 && result.success() && result.rewards().size() == 1
                                && result.rewards().getFirst().id().equals("knife/capture_0");
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                waitTicks = 10; phase++;
            }
            case 5 -> {
                if (!serverWork.isDone() || waitTicks-- > 0) return;
                check(serverWork.join(), "disk-save-and-actual-single-skin-draw");
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve("saved.png")); }
                screen = new CrateManageScreen(new TitleScreen()); mc.setScreen(screen);
                phase++; waitTicks = 10;
            }
            case 6 -> {
                if (waitTicks-- > 0) return;
                var saved = (CrateService.State)get(screen, "draft");
                check(saved.crates.get("woodland").skinWeights.get("knife/capture_0") == 10, "reopen-retains-weight");
                click(MANAGE + "rewards"); edit(UI + "weight", "11");
                serverWork = mc.getSingleplayerServer().submit(() -> CrateService.applyConfigJson(CrateService.configJson()));
                phase++;
            }
            case 7 -> {
                if (!serverWork.isDone()) return;
                check(serverWork.join(), "concurrent-server-revision");
                click(MANAGE + "save"); phase++;
            }
            case 8 -> {
                if ((boolean)get(screen, "pending")) return;
                check((boolean)get(screen, "dirty") && "conflict".equals(get(screen, "status")), "stale-network-save-keeps-draft");
                click(MANAGE + "reload"); clickOn(mc.screen, "gui.no");
                check((boolean)get(screen, "dirty"), "cancel-reload-keeps-draft");
                click(MANAGE + "reload"); clickOn(mc.screen, "gui.yes");
                check(!(boolean)get(screen, "dirty"), "confirm-reload-clears-conflict-draft");
                serverWork = mc.getSingleplayerServer().submit(() -> {
                    CrateService.onServerStopping(); CrateService.onServerStarted();
                    return GSON.fromJson(CrateService.configJson(), CrateService.State.class)
                            .crates.get("woodland").skinWeights.get("knife/capture_0") == 10;
                });
                phase++;
            }
            case 9 -> {
                if (!serverWork.isDone()) return;
                check(serverWork.join(), "service-restart-retains-saved-config");
                Files.write(output.resolve("checks.txt"), checks);
                HabiLotteryMod.LOGGER.info("CRATE_SAVE_NETWORK PASS {} checks", checks.size());
                phase = 12; mc.stop();
            }
        }
    }

    private void click(String key) {
        clickOn(screen, key);
    }
    private void clickOn(net.minecraft.client.gui.screens.Screen target, String key) {
        var name = Component.translatable(key).getString();
        var button = target.children().stream().filter(c -> c instanceof Button b && b.visible && b.active && b.getMessage().getString().equals(name))
                .map(c -> (Button)c).findFirst().orElseThrow(() -> new IllegalStateException("Unreachable button " + name));
        target.mouseClicked(button.getX() + button.getWidth() / 2.0, button.getY() + 10, 0);
    }
    private void edit(String key, String value) {
        var name = Component.translatable(key).getString();
        var box = screen.children().stream().filter(c -> c instanceof EditBox b && b.visible && b.active && b.getMessage().getString().equals(name))
                .map(c -> (EditBox)c).findFirst().orElseThrow();
        box.setValue(value);
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("CRATE_SAVE_NETWORK FAIL " + label);
        checks.add("PASS " + label); HabiLotteryMod.LOGGER.info("CRATE_SAVE_NETWORK PASS {}", label);
    }
    private static Object get(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
}
