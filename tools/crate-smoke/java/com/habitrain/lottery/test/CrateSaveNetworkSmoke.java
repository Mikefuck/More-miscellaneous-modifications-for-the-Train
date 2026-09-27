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
                output = mc.gameDirectory.toPath().resolve(System.getProperty("crateSmoke.out", "save-network-1.1.40")); Files.createDirectories(output);
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
                click(MANAGE + "limits");
                editQuota("white", "120"); editQuota("gold", "2");
                var box = quotaInput("gold");
                var period = screen.children().stream().filter(c -> c instanceof Button b && b.getY() == box.getY() && b.getX() < box.getX())
                        .map(c -> (Button)c).findFirst().orElseThrow();
                screen.mouseClicked(period.getX() + 5, period.getY() + 5, 0);
                click(MANAGE + "save"); phase++;
            }
            case 10 -> {
                if ((boolean)get(screen, "pending")) return;
                check("crates.config_saved".equals(CrateClientNetwork.STATE.configMessage), "output-limit-network-save-acknowledged");
                serverWork = mc.getSingleplayerServer().submit(() -> {
                    try { return checkOutputSettlement(mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst()); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                phase++;
            }
            case 11 -> {
                if (!serverWork.isDone()) return;
                check(serverWork.join(), "material-quota-settlement-complete");
                CrateClientNetwork.requestConfig(); waitTicks = 15; phase++;
            }
            case 12 -> {
                if (waitTicks-- > 0) return;
                var saved = (CrateService.State)get(screen, "latestQuotaState");
                check(saved.outputWeeklyUsed.get("woodland").get("white") == 83L, "real-server-usage-synchronized-to-progress");
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve("output-quotas-saved.png")); }
                Files.write(output.resolve("checks.txt"), checks);
                HabiLotteryMod.LOGGER.info("CRATE_SAVE_NETWORK PASS {} checks", checks.size());
                phase = 13; mc.stop();
            }
        }
    }

    private boolean checkOutputSettlement(net.minecraft.server.level.ServerPlayer player) throws Exception {
        var disk = GSON.fromJson(Files.readString(WorldLotteryPaths.configFile("crates.json")), CrateService.State.class);
        var pool = disk.crates.get("woodland");
        check(pool.outputLimits.get("white").limit == 120 && pool.outputLimits.get("gold").limit == 2
                && pool.outputLimits.get("gold").period.equals("monthly"), "quota-ui-values-persisted-on-server");
        String crate = CrateService.crateItemId("woodland"), key = CrateService.keyItemId("woodland");
        HabiSystemItemApi.grant(player.getUUID(), ResourceLocation.parse(crate), 20);
        HabiSystemItemApi.grant(player.getUUID(), ResourceLocation.parse(key), 20);
        pool.allowSameSkinInOneOpen = true; pool.rollCount = 2;
        check(CrateService.applyConfigJson(GSON.toJson(disk)), "configure-two-materials-per-open");
        var before = CrateService.inventory(player.getUUID());
        var failed = CrateService.open(player, "woodland", key);
        check(!failed.success() && before.equals(CrateService.inventory(player.getUUID()))
                && current().outputMonthlyUsed.get("woodland").get("gold") == 1L, "multi-draw-insufficient-quota-is-atomic");
        var next = current(); next.crates.get("woodland").outputLimits.get("gold").limit = 3;
        check(CrateService.applyConfigJson(GSON.toJson(next)), "increase-cap-without-resetting-usage");
        String request = UUID.randomUUID().toString();
        var success = CrateService.open(player, "woodland", key, request);
        check(success.success() && success.rewards().size() == 2
                && current().outputMonthlyUsed.get("woodland").get("gold") == 3L, "one-opening-counts-two-skins");
        before = CrateService.inventory(player.getUUID());
        check(CrateService.open(player, "woodland", key, request).success()
                && before.equals(CrateService.inventory(player.getUUID()))
                && current().outputMonthlyUsed.get("woodland").get("gold") == 3L, "request-replay-never-double-counts");
        check(!CrateService.open(player, "woodland", key).success()
                && before.equals(CrateService.inventory(player.getUUID())), "exhausted-quality-keeps-crate-and-key");

        next = current(); pool = next.crates.get("woodland"); pool.rollCount = 1;
        pool.skinWeights.put("knife/quota_blue", 100); pool.outputLimits.get("blue").limit = 1;
        check(CrateService.applyConfigJson(GSON.toJson(next)), "configure-blue-alternative");
        success = CrateService.open(player, "woodland", key);
        check(success.success() && success.rewards().getFirst().id().equals("knife/quota_blue")
                && current().outputWeeklyUsed.get("woodland").get("blue") == 1L, "capped-gold-does-not-block-blue");

        next = current(); var other = next.crates.get("cobalt"); other.enabled = true;
        other.skinWeights.put("knife/capture_0", 100); other.outputLimits.get("gold").limit = 1;
        check(CrateService.applyConfigJson(GSON.toJson(next)), "configure-independent-crate");
        HabiSystemItemApi.grant(player.getUUID(), ResourceLocation.parse(CrateService.crateItemId("cobalt")), 2);
        HabiSystemItemApi.grant(player.getUUID(), ResourceLocation.parse(CrateService.keyItemId("cobalt")), 2);
        success = CrateService.open(player, "cobalt", CrateService.keyItemId("cobalt"));
        check(success.success() && current().outputWeeklyUsed.get("cobalt").get("gold") == 1L
                && current().outputWeeklyUsed.get("woodland").get("gold") == 3L, "different-crates-have-independent-output");

        next = current(); pool = next.crates.get("woodland"); pool.skinWeights.replaceAll((id, weight) -> 0);
        pool.extraRewards.clear(); var apples = new CrateService.ExtraReward(); apples.amount = 80; pool.extraRewards.add(apples);
        check(CrateService.applyConfigJson(GSON.toJson(next)), "configure-material-bundle");
        success = CrateService.open(player, "woodland", key);
        check(success.success() && success.rewards().getFirst().amount() == 80
                && current().outputWeeklyUsed.get("woodland").get("white") == 80L, "apple-bundle-counts-eighty-units");
        before = CrateService.inventory(player.getUUID());
        check(!CrateService.open(player, "woodland", key).success() && before.equals(CrateService.inventory(player.getUUID()))
                && current().outputWeeklyUsed.get("woodland").get("white") == 80L, "insufficient-bundle-quota-does-not-partially-award");

        next = current(); pool = next.crates.get("woodland"); pool.extraRewards.clear();
        var card = new CrateService.ExtraReward(); card.type = "card"; card.cardKind = "civilian"; card.amount = 3;
        pool.extraRewards.add(card); pool.outputLimits.get("white").limit = 83;
        check(CrateService.applyConfigJson(GSON.toJson(next)), "configure-card-output");
        success = CrateService.open(player, "woodland", key);
        check(success.success() && success.rewards().getFirst().kind().equals("card")
                && current().outputWeeklyUsed.get("woodland").get("white") == 83L, "cards-count-actual-white-quantity");
        next = current(); next.crates.get("woodland").outputLimits.get("white").period = "monthly";
        next.outputWeeklyUsed.clear(); next.outputMonthlyUsed.clear(); next.pendingOpens.clear();
        check(CrateService.applyConfigJson(GSON.toJson(next)) && current().outputWeeklyUsed.get("woodland").get("white") == 83L,
                "config-save-cannot-forge-server-counters");
        check(!CrateService.open(player, "woodland", key).success(), "switching-period-does-not-reset-usage");
        CrateService.onServerStopping(); CrateService.onServerStarted();
        check(current().outputWeeklyUsed.get("woodland").get("white") == 83L
                && current().outputMonthlyUsed.get("woodland").get("white") == 83L,
                "production-counts-survive-service-restart");
        next = current(); next.crates.get("cobalt").outputLimits.get("gold").limit = 2;
        check(CrateService.applyConfigJson(GSON.toJson(next)), "configure-pending-recovery-check");
        var stateField = CrateService.class.getDeclaredField("state"); stateField.setAccessible(true);
        var live = (CrateService.State)stateField.get(null);
        int previousLifetime = live.skinProduced.get("knife/capture_0");
        live.skinProduced.put("knife/capture_0", Integer.MAX_VALUE); // Force a post-award commit failure.
        String pendingRequest = UUID.randomUUID().toString();
        failed = CrateService.open(player, "cobalt", CrateService.keyItemId("cobalt"), pendingRequest);
        check(!failed.success() && failed.message().equals("crates.pending")
                && current().outputWeeklyUsed.get("cobalt").get("gold") == 1L
                && current().pendingOpens.containsKey(pendingRequest), "commit-exception-rolls-back-quota-counters");
        before = CrateService.inventory(player.getUUID());
        check(!CrateService.open(player, "cobalt", CrateService.keyItemId("cobalt"), pendingRequest).success()
                && before.equals(CrateService.inventory(player.getUUID()))
                && current().outputWeeklyUsed.get("cobalt").get("gold") == 1L,
                "retry-pending-keeps-award-and-counters-idempotent");
        var progress = com.habitrain.lottery.crate.CrateOutputQuota.progress(current(), "cobalt",
                com.habitrain.lottery.api.skin.SkinQuality.GOLD, current().crates.get("cobalt").outputLimits.get("gold"));
        check(progress.reserved() == 1 && progress.remaining() == 0, "pending-material-reserves-final-slot");
        ((CrateService.State)stateField.get(null)).skinProduced.put("knife/capture_0", previousLifetime);
        CrateService.onServerStopping(); CrateService.onServerStarted();
        check(!current().pendingOpens.containsKey(pendingRequest)
                && current().outputWeeklyUsed.get("cobalt").get("gold") == 2L
                && before.equals(CrateService.inventory(player.getUUID())), "restart-recovers-intent-without-double-award");
        check(CrateService.open(player, "cobalt", CrateService.keyItemId("cobalt"), pendingRequest).success()
                && current().outputWeeklyUsed.get("cobalt").get("gold") == 2L,
                "recovered-receipt-replay-does-not-recount");
        return true;
    }

    private static CrateService.State current() { return GSON.fromJson(CrateService.configJson(), CrateService.State.class); }
    private EditBox quotaInput(String quality) {
        String name = Component.translatable(MANAGE + "output_limit_label", Component.translatable("skin.habitrain_lottery.quality." + quality)).getString();
        return screen.children().stream().filter(c -> c instanceof EditBox b && b.getMessage().getString().equals(name))
                .map(c -> (EditBox)c).findFirst().orElseThrow();
    }
    private void editQuota(String quality, String value) { quotaInput(quality).setValue(value); }

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
