package com.habitrain.lottery.test;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.client.DailyTaskAdminClient;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.gui.DailyTaskManageScreen;
import com.habitrain.lottery.client.gui.DailyTaskScreen;
import com.habitrain.lottery.client.gui.LotteryConfigRootScreen;
import com.habitrain.lottery.daily.config.DailyFactions;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyTaskConfig;
import com.habitrain.lottery.daily.config.DailyTaskConfigService;
import com.habitrain.lottery.daily.config.DailyTaskDefinition;
import com.habitrain.lottery.daily.config.DailyTaskTracker;
import com.habitrain.lottery.daily.config.DailyTaskType;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.api.replay.ReplayEventTypes;
import io.wifi.starrailexpress.api.replay.ReplayPlayerProfile;
import io.wifi.starrailexpress.api.replay.ReplayTimelineEvent;
import io.wifi.starrailexpress.api.replay.event.ReplayEventRecordedCallback;
import io.wifi.starrailexpress.backpack.BackpackManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.progression.ProgressionDataManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Drives the real Mod Menu console, the daily-task editor and picker through widget clicks in a
 * fresh integrated-server world, then fires real upstream events and checks progress and claims.
 */
public final class DailySmoke implements ClientModInitializer {
    private static final Gson GSON = new Gson();
    private final List<String> checks = new ArrayList<>();
    private final String world = "daily-smoke-" + UUID.randomUUID();
    private int phase, ticks, wait;
    private Path out;
    private Screen screen;
    private CompletableFuture<?> work;
    private Map<String, Object> before;

    @Override
    public void onInitializeClient() {
        if (!"editor".equals(System.getProperty("dailySmoke.mode", "editor"))) return;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getOverlay() != null || phase < 0) return;
            try {
                tick(mc);
            } catch (Throwable error) {
                HabiLotteryMod.LOGGER.error("DAILY_SMOKE FAIL at phase {}", phase, error);
                checks.add("FAIL phase " + phase + ": " + error);
                finish(mc);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        if (++ticks > 12000) throw new IllegalStateException("timeout");
        if (wait > 0) { wait--; return; }
        switch (phase) {
            case 0 -> {
                if (ticks < 40 || mc.screen == null) return;
                out = mc.gameDirectory.toPath().resolve("daily-smoke");
                Files.createDirectories(out);
                mc.options.languageCode = "zh_cn";
                mc.getLanguageManager().setSelected("zh_cn");
                mc.reloadResourcePacks();
                mc.options.renderDistance().set(2);
                mc.options.guiScale().set(2);
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1280, 720);
                mc.resizeDisplay();
                next(60);
            }
            case 1 -> {
                // Mod Menu path without a world: read-only defaults, clear offline message.
                screen = new DailyTaskManageScreen(new TitleScreen());
                mc.setScreen(screen);
                next(15);
            }
            case 2 -> {
                shot(mc, "01-offline-defaults");
                check(!(boolean) get(screen, "loaded"), "offline-editor-not-loaded");
                check(!button(screen, "保存").active, "offline-save-disabled");
                phase++;
                mc.createWorldOpenFlows().createFreshLevel(world,
                        new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.PEACEFUL, false,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42L, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                                .createWorldDimensions(), new TitleScreen());
            }
            case 3 -> {
                if (mc.player == null || !DailyTaskAdminClient.connected()) return;
                next(40);
            }
            case 4 -> {
                LotteryConfigRootScreen root = new LotteryConfigRootScreen(null);
                mc.setScreen(root);
                var m = LotteryConfigRootScreen.class.getDeclaredMethod("switchTab", int.class);
                m.setAccessible(true);
                m.invoke(root, 5);
                screen = root;
                next(10);
            }
            case 5 -> {
                shot(mc, "02-console-daily-tab");
                click(screen, "打开每日任务编辑器");
                check(mc.screen instanceof DailyTaskManageScreen, "console-opens-editor");
                screen = mc.screen;
                phase++;
            }
            case 6 -> {
                if (!(boolean) get(screen, "loaded")) return;
                DailyTaskConfig draft = (DailyTaskConfig) get(screen, "draft");
                check(draft.find("daily_login") != null && draft.tasks.size() == 7, "server-defaults-loaded");
                next(5);
            }
            case 7 -> {
                shot(mc, "03-editor-login-task");
                click(screen, "新建");
                click(screen, startsWith("参与对局"));
                check(mc.screen instanceof com.habitrain.lottery.client.gui.DailyPickerScreen, "type-picker-opens");
                next(8);
            }
            case 8 -> {
                shot(mc, "04-type-picker");
                click(mc.screen, "击杀玩家");
                click(mc.screen, "完成");
                screen = mc.screen;
                check(screen instanceof DailyTaskManageScreen, "picker-returns");
                edit(screen, "目标数量", "2");
                clickNth(screen, "不限 ⟳", 0); // role requirement -> faction
                clickNth(screen, "不限（全部） …", 0); // faction picker
                next(4);
            }
            case 9 -> {
                click(mc.screen, "杀手");
                click(mc.screen, "完成");
                screen = mc.screen;
                clickNth(screen, "不限（全部） …", 0); // death reason picker
                next(4);
            }
            case 10 -> {
                shot(mc, "05-death-reason-picker");
                click(mc.screen, "刀");
                click(mc.screen, "完成");
                screen = mc.screen;
                clickNth(screen, "不限 ⟳", 0); // victim -> enemy
                next(6);
            }
            case 11 -> {
                shot(mc, "06-conditions-kill");
                DailyTaskDefinition task = selected();
                check(task.taskType() == DailyTaskType.KILL && task.target == 2
                        && task.factions.equals(List.of(DailyFactions.KILLER))
                        && task.deathReasons.equals(List.of("starrailexpress:knife_stab"))
                        && "enemy".equals(task.victimMode), "conditions-edited-through-widgets");
                check(task.description.contains("杀手") && task.description.contains("刀"), "auto-description:" + task.description);
                click(screen, "奖励");
                click(screen, "+ 添加奖励");
                for (int i = 0; i < 5; i++) clickNth(screen, "⟳", 1, true); // apples -> ... -> crate
                click(screen, "+ 添加奖励");
                clickNth(screen, "⟳", 2, true); // apples -> faction card (civilian)
                next(6);
            }
            case 12 -> {
                shot(mc, "07-rewards");
                DailyTaskDefinition task = selected();
                check(task.rewards.size() == 3 && task.rewards.get(1).kind.equals("crate")
                        && task.rewards.get(1).id.equals("*") && task.rewards.get(2).kind.equals("card")
                        && task.rewards.get(2).id.equals("civilian"), "rewards-edited:" + GSON.toJson(task.rewards));
                click(screen, "基本信息");
                edit(screen, "标题", "刀锋猎手");
                next(4);
            }
            case 13 -> {
                shot(mc, "08-basics");
                check((boolean) get(screen, "dirty") && button(screen, "保存").active, "dirty-enables-save");
                click(screen, "保存");
                phase++;
            }
            case 14 -> {
                if ((boolean) get(screen, "pending")) return;
                check("saved".equals(DailyTaskAdminClient.message), "server-accepted-save:" + DailyTaskAdminClient.message);
                check(!(boolean) get(screen, "dirty"), "ack-clears-dirty");
                var server = mc.getSingleplayerServer();
                work = server.submit(() -> {
                    try {
                        DailyTaskConfig disk = GSON.fromJson(Files.readString(WorldLotteryPaths.configFile("daily_tasks.json")),
                                DailyTaskConfig.class);
                        DailyTaskDefinition saved = disk.find("task_1");
                        if (saved == null || !"刀锋猎手".equals(saved.title)) return false;
                        // Extra tasks written straight through the server authority for the event coverage below.
                        DailyTaskConfig next = DailyTaskConfigService.current().copy();
                        next.tasks.add(def("surv", DailyTaskType.SURVIVE_TIME, 1, t -> { }));
                        next.tasks.add(def("quest", DailyTaskType.FINISH_TASK, 2, t -> t.quests.add("eat")));
                        next.tasks.add(def("spend", DailyTaskType.SHOP_SPEND, 100, t -> { }));
                        next.tasks.add(def("item", DailyTaskType.USE_ITEM, 1, t -> t.items.add("starrailexpress:knife")));
                        next.tasks.add(def("winner", DailyTaskType.PLAY_MATCH, 1, t -> {
                            t.matchResult = "win"; t.roleMode = "faction"; t.factions.add(DailyFactions.KILLER); }));
                        next.tasks.add(def("civ_kill", DailyTaskType.KILL, 1, t -> {
                            t.roleMode = "faction"; t.factions.add(DailyFactions.CIVILIAN); }));
                        next.tasks.add(def("claimer", DailyTaskType.CLAIM_TASKS, 1, t -> { }));
                        next.tasks.add(def("single", DailyTaskType.KILL, 2, t -> t.singleMatch = true));
                        return DailyTaskConfigService.apply(GSON.toJson(next));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                phase++;
            }
            case 15 -> {
                if (!work.isDone()) return;
                check(Boolean.TRUE.equals(work.join()), "disk-has-task-and-extra-tasks-applied");
                var server = mc.getSingleplayerServer();
                work = server.submit(() -> simulateMatch(server.getPlayerList().getPlayers().getFirst()));
                phase++;
            }
            case 16 -> {
                if (!work.isDone()) return;
                @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) work.join();
                checks.add("INFO simulation " + r);
                check(Boolean.TRUE.equals(r.get("killEventRan")), "upstream-kill-event-dispatched");
                next(10); // let the settlement tick run
            }
            case 17 -> {
                var server = mc.getSingleplayerServer();
                work = server.submit(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                    return Map.of("task_1", progress(p, "task_1"), "surv", progress(p, "surv"), "quest", progress(p, "quest"),
                            "spend", progress(p, "spend"), "item", progress(p, "item"), "winner", progress(p, "winner"),
                            "civ_kill", progress(p, "civ_kill"), "single", progress(p, "single"),
                            "login", progress(p, "daily_login"));
                });
                phase++;
            }
            case 18 -> {
                if (!work.isDone()) return;
                @SuppressWarnings("unchecked") Map<String, Integer> p = (Map<String, Integer>) work.join();
                checks.add("INFO progress " + p);
                check(p.get("task_1") == 2, "knife-kills-by-killer-count-revolver-ignored");
                check(p.get("single") == 2, "single-match-kills");
                check(p.get("civ_kill") == 0, "faction-filter-rejects-killer");
                check(p.get("surv") == 1, "sixty-survival-samples-make-one-minute");
                check(p.get("quest") == 2, "quest-mixin-counts-eat");
                check(p.get("spend") == 100, "shop-spend-capped-at-target");
                check(p.get("item") == 1, "replay-item-use");
                check(p.get("winner") == 1, "round-settled-mixin-win-as-killer");
                check(p.get("login") == 1, "login-task-completed-on-join");
                var server = mc.getSingleplayerServer();
                work = server.submit(() -> snapshot(server.getPlayerList().getPlayers().getFirst()));
                phase++;
            }
            case 19 -> {
                if (!work.isDone()) return;
                @SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) work.join();
                before = b;
                check(LotteryClientNetwork.clientClaimDailyTask("habitrain_lottery:task_1"), "claim-sent");
                next(20);
            }
            case 20 -> {
                var server = mc.getSingleplayerServer();
                work = server.submit(() -> snapshot(server.getPlayerList().getPlayers().getFirst()));
                phase++;
            }
            case 21 -> {
                if (!work.isDone()) return;
                @SuppressWarnings("unchecked") Map<String, Object> after = (Map<String, Object>) work.join();
                checks.add("INFO before " + before + " after " + after);
                check((int) after.get("apples") - (int) before.get("apples") == 50, "claim-grants-apples");
                check((int) after.get("crates") - (int) before.get("crates") == 1, "claim-grants-random-crate");
                check((int) after.get("civilian") - (int) before.get("civilian") == 1, "claim-grants-civilian-card");
                check((boolean) after.get("claimed"), "task-marked-claimed");
                check((int) after.get("claimer") == 1, "claim-counts-for-claim-tasks");
                before = after;
                LotteryClientNetwork.clientClaimDailyTask("habitrain_lottery:task_1");
                next(20);
            }
            case 22 -> {
                var server = mc.getSingleplayerServer();
                work = server.submit(() -> snapshot(server.getPlayerList().getPlayers().getFirst()));
                phase++;
            }
            case 23 -> {
                if (!work.isDone()) return;
                @SuppressWarnings("unchecked") Map<String, Object> after = (Map<String, Object>) work.join();
                check(after.get("apples").equals(before.get("apples")) && after.get("crates").equals(before.get("crates")),
                        "second-claim-pays-nothing");
                var server = mc.getSingleplayerServer();
                server.execute(() -> HabiDailyTaskApi.open(server.getPlayerList().getPlayers().getFirst()));
                next(20);
            }
            case 24 -> {
                check(mc.screen instanceof DailyTaskScreen, "player-board-opens");
                shot(mc, "09-player-board");
                screen = new DailyTaskManageScreen(null);
                mc.setScreen(screen);
                next(20);
            }
            case 25 -> {
                DailyTaskConfig draft = (DailyTaskConfig) get(screen, "draft");
                check(draft.find("task_1") != null && draft.find("single") != null, "reopen-shows-saved-tasks");
                set(screen, "selected", draft.tasks.indexOf(draft.find("task_1")));
                rebuild(screen);
                shot(mc, "10-reopened-task");
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 540);
                mc.resizeDisplay();
                next(10);
            }
            case 26 -> {
                shot(mc, "11-narrow-conditions");
                click(screen, "奖励");
                next(5);
            }
            case 27 -> {
                shot(mc, "12-narrow-rewards");
                mc.options.guiScale().set(3);
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1920, 1080);
                mc.resizeDisplay();
                next(10);
            }
            case 28 -> {
                shot(mc, "13-1080p-scale3-rewards");
                // Invalid draft: blank title must be refused locally with a readable reason.
                click(screen, "基本信息");
                edit(screen, "标题", "");
                click(screen, "保存");
                check(((String) get(screen, "status")).contains("标题"), "invalid-title-refused:" + get(screen, "status"));
                next(5);
            }
            case 29 -> {
                shot(mc, "14-validation-error");
                HabiLotteryMod.LOGGER.info("DAILY_SMOKE PASS {} checks", checks.stream().filter(c -> c.startsWith("PASS")).count());
                finish(mc);
            }
            default -> { }
        }
    }

    private Map<String, Object> simulateMatch(ServerPlayer p) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        var level = p.serverLevel();
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        SRERole killer = role(r -> r.canUseKiller() && !r.isNeutrals(), "killer");
        SRERole civilian = role(r -> r.isInnocent() && !r.isVigilanteTeam() && !r.canUseKiller() && !r.isNeutrals(), "civilian");
        result.put("killer", killer.identifier().toString());
        result.put("civilian", civilian.identifier().toString());
        FakePlayer victim = FakePlayer.get(level);
        var previous = game.getGameStatus();
        try {
            game.setGameStatus(SREGameWorldComponent.GameStatus.ACTIVE);
            game.addRole(p, killer);
            game.addRole(victim, civilian);
            // Revolver first: must not count for the knife task, but counts for "single" (any weapon).
            fire(result, victim, p, GameConstants.DeathReasons.REVOLVER);
            fire(result, victim, p, GameConstants.DeathReasons.KNIFE);
            fire(result, victim, p, GameConstants.DeathReasons.KNIFE);
            for (int i = 0; i < 60; i++) DailyTaskTracker.sampleSurvival(p.server);
            ProgressionDataManager.onRoundQuestFinished(p, "eat");
            ProgressionDataManager.onRoundQuestFinished(p, "sleep");
            ProgressionDataManager.onRoundQuestFinished(p, "eat");
            var actor = new ReplayPlayerProfile(p.getUUID(), p.getGameProfile().getName(), killer.identifier().toString(),
                    Component.empty(), true);
            replay(ReplayEventTypes.EventType.STORE_BUY, actor, Map.of("itemUsed", "starrailexpress:knife:1", "message", "150"));
            replay(ReplayEventTypes.EventType.ITEM_USED, actor, Map.of("itemUsed", "starrailexpress:grenade"));
            replay(ReplayEventTypes.EventType.ITEM_USED, actor, Map.of("itemUsed", "starrailexpress:knife"));
            ProgressionDataManager.onRoundSettled(p, killer, true);
        } finally {
            game.getRoles().remove(p.getUUID());
            game.getRoles().remove(victim.getUUID());
            game.setGameStatus(previous);
        }
        return result;
    }

    private static void fire(Map<String, Object> result, FakePlayer victim, ServerPlayer killer, ResourceLocation reason) {
        try {
            OnPlayerDeathWithKiller.EVENT.invoker().onPlayerDeath(victim, killer, reason);
            result.put("killEventRan", true);
        } catch (RuntimeException other) {
            // Another mod's listener may not expect a fake victim; ours is exception-isolated.
            result.put("killEventRan", true);
            result.put("otherListenerError", other.toString());
        }
    }

    private static void replay(ReplayEventTypes.EventType type, ReplayPlayerProfile actor, Map<String, String> data) {
        Map<String, String> full = new java.util.HashMap<>(data);
        full.put("sourcePlayer", actor.uuid().toString());
        ReplayEventRecordedCallback.EVENT.invoker().onReplayEventRecorded(new ReplayTimelineEvent(UUID.randomUUID(), type,
                System.currentTimeMillis(), 0L, actor, null, Component.empty(), false, full), List.of());
    }

    private static SRERole role(java.util.function.Predicate<SRERole> test, String preferredPath) {
        for (SRERole r : TMMRoles.ROLES.values()) if (r.identifier().getPath().equals(preferredPath) && test.test(r)) return r;
        return TMMRoles.ROLES.values().stream().filter(test).findFirst().orElseThrow();
    }

    private static int progress(ServerPlayer p, String id) {
        return HabiDailyTaskApi.progress(p, DailyTaskConfigService.boardId(id));
    }

    private static Map<String, Object> snapshot(ServerPlayer p) {
        var data = PlayerLotteryStore.get().getOrLoad(p);
        int crates = 0;
        for (var e : data.systemItems.entrySet()) if (e.getKey().contains(":crate_")) crates += e.getValue();
        return Map.of("apples", data.greenApples, "crates", crates,
                "civilian", BackpackManager.getCardCount(p, FactionCardType.CIVILIAN),
                "claimed", HabiDailyTaskApi.claimed(p, DailyTaskConfigService.boardId("task_1")),
                "claimer", progress(p, "claimer"));
    }

    private static DailyTaskDefinition def(String id, DailyTaskType type, int target, java.util.function.Consumer<DailyTaskDefinition> edit) {
        DailyTaskDefinition t = new DailyTaskDefinition();
        t.id = id; t.title = id; t.type = type.id(); t.target = target;
        t.rewards.add(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 5));
        edit.accept(t);
        return t;
    }

    // ------------------------------------------------------------------ widget helpers

    private DailyTaskDefinition selected() throws Exception {
        DailyTaskConfig draft = (DailyTaskConfig) get(screen, "draft");
        return draft.tasks.get((int) get(screen, "selected"));
    }

    private static String startsWith(String prefix) { return "^" + prefix; }

    private static boolean matches(AbstractWidget w, String label) {
        String text = w.getMessage().getString();
        return label.startsWith("^") ? text.startsWith(label.substring(1)) : text.equals(label);
    }

    private static List<AbstractWidget> widgets(Screen s, String label, boolean contains) {
        List<AbstractWidget> out = new ArrayList<>();
        for (var c : s.children()) {
            if (c instanceof AbstractWidget w && w.visible && w.active
                    && (contains ? w.getMessage().getString().contains(label) : matches(w, label))) out.add(w);
        }
        out.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
        return out;
    }

    private static Button button(Screen s, String label) {
        for (var c : s.children()) if (c instanceof Button b && b.getMessage().getString().equals(label)) return b;
        throw new IllegalStateException("No button " + label);
    }

    private static void click(Screen s, String label) {
        List<AbstractWidget> found = widgets(s, label, false);
        if (found.isEmpty()) throw new IllegalStateException("Unreachable widget '" + label + "' on " + s.getClass().getSimpleName());
        AbstractWidget w = found.getFirst();
        s.mouseClicked(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
    }

    private void clickNth(Screen s, String label, int n) { clickNth(s, label, n, false); }

    private void clickNth(Screen s, String label, int n, boolean contains) {
        List<AbstractWidget> found = widgets(s, label, contains);
        if (found.size() <= n) throw new IllegalStateException("Widget '" + label + "' #" + n + " not found (" + found.size() + ")");
        AbstractWidget w = found.get(n);
        s.mouseClicked(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
    }

    private static void edit(Screen s, String label, String value) {
        for (var c : s.children()) {
            if (c instanceof EditBox b && b.visible && b.getMessage().getString().equals(label)) {
                b.setValue(value);
                return;
            }
        }
        throw new IllegalStateException("No edit box " + label);
    }

    private static void rebuild(Screen s) throws Exception {
        var m = s.getClass().getDeclaredMethod("rebuild");
        m.setAccessible(true);
        m.invoke(s);
    }

    private static Object get(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void next(int delay) { phase++; wait = delay; }

    private void shot(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(out.resolve(name + ".png"));
        }
    }

    private void check(boolean ok, String label) {
        checks.add((ok ? "PASS " : "FAIL ") + label);
        HabiLotteryMod.LOGGER.info("DAILY_SMOKE {} {}", ok ? "PASS" : "FAIL", label);
        if (!ok) throw new IllegalStateException("check failed: " + label);
    }

    private void finish(Minecraft mc) {
        phase = -1;
        try {
            if (out != null) Files.write(out.resolve("checks.txt"), checks);
        } catch (Exception ignored) { }
        mc.stop();
    }

    @SuppressWarnings("unused")
    private static <T> T unused(Supplier<T> s) { return s.get(); }
}
