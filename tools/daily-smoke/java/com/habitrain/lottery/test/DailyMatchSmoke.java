package com.habitrain.lottery.test;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.client.DailyTaskAdminClient;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.gui.DailyTaskScreen;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.daily.config.DailyFactions;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyRoleFacts;
import com.habitrain.lottery.daily.config.DailyTaskConfig;
import com.habitrain.lottery.daily.config.DailyTaskConfigService;
import com.habitrain.lottery.daily.config.DailyTaskDefinition;
import com.habitrain.lottery.daily.config.DailyTaskType;
import com.habitrain.lottery.network.CardUseConfirmC2S;
import com.habitrain.lottery.network.CrateNetwork;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.mojang.authlib.GameProfile;
import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.GameMode;
import io.wifi.starrailexpress.api.RoleMethodDispatcher;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.backpack.BackpackManager;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import io.wifi.starrailexpress.event.OnRevolverUsed;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.exmo.sre.meeting.MeetingManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Plays one real upstream match lifecycle in a fresh integrated-server world and checks every
 * configurable daily task type (and its filters) against the progress the tracker recorded.
 *
 * <p>Singleplayer cannot satisfy murder's six-player minimum, so a test-only one-player mode is
 * registered; everything else runs through upstream: {@code trueStartGame} → fade →
 * {@code initializeGame} → safe time → {@code OnGameTrueStarted} → {@code killPlayer} /
 * replay recorder / quest dispatcher / meeting manager → {@code stopGame} → {@code finalizeGame}
 * ({@code OnGameEnd} + {@code recordWinStats} → {@code onRoundSettled}). Crates, cards and claims
 * go through the real C2S packets from the client.</p>
 *
 * <p>Enabled with {@code -PdailySmokeMode=match}.</p>
 */
public final class DailyMatchSmoke implements ClientModInitializer {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation MODE_ID = ResourceLocation.fromNamespaceAndPath("habitrain_daily_smoke", "solo");
    private static final int SURVIVE_SECONDS = 65;

    private final List<String> checks = new ArrayList<>();
    private final String world = "daily-match-" + UUID.randomUUID();
    private int phase, ticks, wait;
    private Path out;
    private CompletableFuture<?> work;
    private volatile boolean trueStarted;
    private long survivalStart;
    private Map<String, Integer> progress = Map.of();
    private Map<String, Object> before = Map.of();
    private String killerRoleId = "";

    @Override
    public void onInitializeClient() {
        if (!"match".equals(System.getProperty("dailySmoke.mode"))) return;
        SREGameModes.registerGameMode(new SoloMode());
        OnGameTrueStarted.EVENT.register(level -> trueStarted = true);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getOverlay() != null || phase < 0) return;
            try {
                tick(mc);
            } catch (Throwable error) {
                HabiLotteryMod.LOGGER.error("DAILY_MATCH FAIL at phase {}", phase, error);
                checks.add("FAIL phase " + phase + ": " + error);
                finish(mc);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        if (++ticks > 20 * 60 * 12) throw new IllegalStateException("timeout");
        if (wait > 0) { wait--; return; }
        switch (phase) {
            case 0 -> {
                if (ticks < 40 || mc.screen == null) return;
                out = mc.gameDirectory.toPath().resolve("daily-match-smoke");
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
                phase++;
                mc.createWorldOpenFlows().createFreshLevel(world,
                        new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42L, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                                .createWorldDimensions(), new TitleScreen());
            }
            case 2 -> {
                if (mc.player == null || !DailyTaskAdminClient.connected()) return;
                next(40);
            }
            case 3 -> {
                // Every task type + filter variants, saved through the same server authority as the editor.
                work = server(mc, () -> {
                    boolean ok = DailyTaskConfigService.apply(GSON.toJson(buildConfig()));
                    if (!ok) throw new IllegalStateException("config rejected: " + DailyTaskConfigService.lastError());
                    ServerPlayer p = me(mc);
                    // Lobby-only card use: a self-select before the match (a direct card after it).
                    LocalBackpackStore.addSelfSelectCards(p.getUUID(), 1);
                    return DailyTaskConfigService.current().tasks.size();
                });
                phase++;
            }
            case 4 -> {
                if (!work.isDone()) return;
                checks.add("INFO configured tasks: " + work.join());
                check(true, "config-with-all-14-types-accepted");
                ClientPlayNetworking.send(new CardUseConfirmC2S("self_select", "self", selfSelectRole()));
                next(20);
            }
            case 5 -> {
                work = server(mc, () -> {
                    ServerPlayer p = me(mc);
                    ServerLevel level = p.serverLevel();
                    AreasWorldComponent areas = AreasWorldComponent.KEY.get(level);
                    if (areas.getRoomCount() < 1) areas.setRoomCount(1);
                    areas.areasSettings.meetingEnabled = true;
                    areas.areasSettings.meetingStartCooldown = 0;
                    // No train map: the start teleport drops the player by the play-area offset.
                    level.getGameRules().getRule(GameRules.RULE_FALL_DAMAGE).set(false, level.getServer());
                    GameUtils.setForcedReadyPlayers(List.of(p.getUUID()));
                    GameUtils.trueStartGame(level, SREGameModes.GAME_MODES.get(MODE_ID), GameConstants.getInTicks(10, 0));
                    return SREGameWorldComponent.KEY.get(level).getGameStatus().name();
                });
                phase++;
            }
            case 6 -> {
                if (!work.isDone()) return;
                checks.add("INFO after trueStartGame: " + work.join());
                phase++;
            }
            case 7 -> {
                // STARTING → fade → initializeGame → ACTIVE, then safe time → OnGameTrueStarted.
                if (status(mc) != SREGameWorldComponent.GameStatus.ACTIVE) return;
                check(true, "match-active");
                shot(mc, "01-match-active");
                phase++;
            }
            case 8 -> {
                if (status(mc) != SREGameWorldComponent.GameStatus.ACTIVE)
                    throw new IllegalStateException("match ended during safe time: " + status(mc));
                if (!GameUtils.isPlayerAliveAndSurvival(me(mc)))
                    throw new IllegalStateException("smoke player died during safe time");
                if (!trueStarted) return;
                check(true, "safe-time-over-true-started");
                work = server(mc, () -> playMatch(me(mc)));
                phase++;
            }
            case 9 -> {
                if (!work.isDone()) return;
                checks.add("INFO in-match actions " + work.join());
                survivalStart = System.currentTimeMillis();
                wait = 20;
                phase++;
            }
            case 10 -> {
                work = server(mc, () -> { MeetingManager.endMeeting(true); return true; });
                shot(mc, "02-in-match");
                phase++;
            }
            case 11 -> {
                // Real survival sampler: stay alive for more than a minute of real ticks.
                if (System.currentTimeMillis() - survivalStart < SURVIVE_SECONDS * 1000L) return;
                work = server(mc, () -> {
                    ServerLevel level = me(mc).serverLevel();
                    SREGameRoundEndComponent.KEY.get(level).setWinStatus(GameUtils.WinStatus.KILLERS);
                    GameUtils.stopGame(level);
                    return true;
                });
                phase++;
            }
            case 12 -> {
                if (!work.isDone() || status(mc) != SREGameWorldComponent.GameStatus.INACTIVE) return;
                check(true, "match-finalized");
                next(20); // settlement runs on the tick after OnGameEnd
            }
            case 13 -> {
                // Lobby: open two woodland crates and use two cards through the real C2S packets.
                work = server(mc, () -> {
                    ServerPlayer p = me(mc);
                    CrateService.State state = GSON.fromJson(CrateService.configJson(), CrateService.State.class);
                    CrateService.CratePool pool = state.crates.get("woodland");
                    pool.enabled = true;
                    pool.skinWeights.replaceAll((id, weight) -> 0);
                    pool.extraRewards.clear();
                    CrateService.ExtraReward apples = new CrateService.ExtraReward();
                    apples.amount = 1;
                    pool.extraRewards.add(apples);
                    if (!CrateService.applyConfigJson(GSON.toJson(state))) throw new IllegalStateException("crate config");
                    HabiSystemItemApi.grant(p.getUUID(), ResourceLocation.parse(CrateService.crateItemId("woodland")), 2);
                    HabiSystemItemApi.grant(p.getUUID(), ResourceLocation.parse(CrateService.keyItemId("woodland")), 2);
                    LocalBackpackStore.addLimitBreakCards(p.getUUID(), 1);
                    BackpackManager.addCard(p, FactionCardType.CIVILIAN, 1);
                    return true;
                });
                phase++;
            }
            case 14 -> {
                if (!work.isDone()) return;
                work.join();
                sendOpen();
                ClientPlayNetworking.send(new CardUseConfirmC2S("limit_break", "bonus", ""));
                next(30); // crate open is rate-limited to one per 800 ms
            }
            case 15 -> {
                sendOpen();
                ClientPlayNetworking.send(new CardUseConfirmC2S("civilian", "direct", ""));
                next(30);
            }
            case 16 -> {
                work = server(mc, () -> readProgress(me(mc)));
                phase++;
            }
            case 17 -> {
                if (!work.isDone()) return;
                @SuppressWarnings("unchecked") Map<String, Integer> p = (Map<String, Integer>) work.join();
                progress = p;
                checks.add("INFO progress " + p);
                verifyProgress();
                work = server(mc, () -> snapshot(me(mc)));
                phase++;
            }
            case 18 -> {
                if (!work.isDone()) return;
                @SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) work.join();
                before = b;
                // gun_hit is 1/5: an unfinished task must be refused and pay nothing.
                check(LotteryClientNetwork.clientClaimDailyTask("habitrain_lottery:gun_hit"), "claim-unfinished-sent");
                next(20); // claims are rate-limited to one per 350 ms
            }
            case 19 -> {
                check(LotteryClientNetwork.clientClaimDailyTask("habitrain_lottery:meeting"), "claim-1-sent");
                next(20);
            }
            case 20 -> {
                check(LotteryClientNetwork.clientClaimDailyTask("habitrain_lottery:play_win_killer"), "claim-2-sent");
                next(20);
            }
            case 21 -> {
                work = server(mc, () -> snapshot(me(mc)));
                phase++;
            }
            case 22 -> {
                if (!work.isDone()) return;
                @SuppressWarnings("unchecked") Map<String, Object> after = (Map<String, Object>) work.join();
                checks.add("INFO before " + before + " after " + after);
                check(!(boolean) after.get("gun_hit_claimed"), "unfinished-task-refused");
                check((int) after.get("apples") - (int) before.get("apples") == 10, "two-claims-grant-5-apples-each");
                check((int) after.get("claimer") == 2, "claim_tasks-counts-two-claims");
                var server = mc.getSingleplayerServer();
                server.execute(() -> HabiDailyTaskApi.open(me(mc)));
                next(30);
            }
            case 23 -> {
                check(mc.screen instanceof DailyTaskScreen, "player-board-opens");
                shot(mc, "03-player-board");
                long fails = checks.stream().filter(c -> c.startsWith("FAIL")).count();
                HabiLotteryMod.LOGGER.info("DAILY_MATCH {} {} checks, {} failed",
                        fails == 0 ? "PASS" : "FAIL", checks.stream().filter(c -> c.startsWith("PASS")).count(), fails);
                finish(mc);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ the match

    private Map<String, Object> playMatch(ServerPlayer me) {
        Map<String, Object> r = new LinkedHashMap<>();
        ServerLevel level = me.serverLevel();
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        SRERole myRole = game.getRole(me);
        r.put("myRole", myRole == null ? null : myRole.identifier().toString());
        r.put("myFaction", DailyRoleFacts.faction(myRole));
        SRERole civilian = role(x -> x.isInnocent() && !x.isVigilanteTeam() && !x.canUseKiller() && !x.isNeutrals(), "civilian");
        SRERole killer = role(x -> x.canUseKiller() && !x.isNeutrals(), "killer");

        // Kills through upstream GameUtils.killPlayer (shields, redirection, OnPlayerDeathWithKiller).
        FakePlayer v1 = victim(level, me, "Victim_Civ1", civilian, game);
        FakePlayer v2 = victim(level, me, "Victim_Civ2", civilian, game);
        FakePlayer v3 = victim(level, me, "Victim_Killer", killer, game);
        kill(r, "v1", v1, me, GameConstants.DeathReasons.KNIFE);
        kill(r, "v2", v2, me, GameConstants.DeathReasons.REVOLVER);
        kill(r, "v3", v3, me, GameConstants.DeathReasons.KNIFE);

        // In-match tasks through the role dispatcher (→ ProgressionDataManager.onRoundQuestFinished).
        RoleMethodDispatcher.callOnFinishQuest(me, "eat");
        RoleMethodDispatcher.callOnFinishQuest(me, "sleep");
        RoleMethodDispatcher.callOnFinishQuest(me, "eat");

        // Shop / item / skill / special actions through the upstream replay recorder.
        ResourceLocation knife = ResourceLocation.parse("starrailexpress:knife");
        ResourceLocation grenade = ResourceLocation.parse("starrailexpress:grenade");
        SRE.REPLAY_MANAGER.recordStoreBuy(me.getUUID(), knife, 1, 150);
        SRE.REPLAY_MANAGER.recordStoreBuy(me.getUUID(), grenade, 1, 200);
        SRE.REPLAY_MANAGER.recordItemUse(me.getUUID(), knife);
        SRE.REPLAY_MANAGER.recordItemUse(me.getUUID(), grenade);
        SRE.REPLAY_MANAGER.recordSkillUsed(me.getUUID(), ResourceLocation.parse("noellesroles:test_skill"));
        SRE.REPLAY_MANAGER.recordBombDefuse(me.getUUID(), null);
        SRE.REPLAY_MANAGER.recordBombDetonate(me.getUUID(), v1.getUUID());
        SRE.REPLAY_MANAGER.recordTrapTriggered(me.getUUID(), v1.getUUID());
        SRE.REPLAY_MANAGER.recordDisguise(me.getUUID());
        SRE.REPLAY_MANAGER.recordDoorPry(me.getUUID(), BlockPos.ZERO);
        SRE.REPLAY_MANAGER.recordDoorSeal(me.getUUID(), BlockPos.ZERO);
        SRE.REPLAY_MANAGER.recordRopePull(me.getUUID(), v1.getUUID());
        // Revolver hit: the event GunShootPayload fires on a hit (the shot itself needs a client aim).
        OnRevolverUsed.EVENT.invoker().onPlayerShoot(me, v2);

        // Meeting through the real manager (emergency bypasses the start cooldown).
        r.put("meetingStarted", MeetingManager.startMeeting(level, me, null, true));
        return r;
    }

    private static FakePlayer victim(ServerLevel level, ServerPlayer near, String name, SRERole role, SREGameWorldComponent game) {
        FakePlayer v = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        v.moveTo(near.getX() + 1, near.getY(), near.getZ());
        v.setGameMode(GameType.ADVENTURE);
        game.addRole(v, role);
        return v;
    }

    private static void kill(Map<String, Object> r, String label, FakePlayer victim, ServerPlayer killer, ResourceLocation reason) {
        try {
            GameUtils.killPlayer(victim, false, killer, reason);
            r.put(label, "killed spectator=" + victim.isSpectator());
        } catch (RuntimeException error) {
            r.put(label, "error " + error);
        }
    }

    // ------------------------------------------------------------------ config

    private DailyTaskConfig buildConfig() {
        DailyTaskConfig next = DailyTaskConfigService.current().copy();
        // Keep daily_login; drop the disabled templates so the matrix fits in MAX_TASKS.
        next.tasks.removeIf(d -> !d.enabled && !DailyTaskConfig.LOGIN_TASK_ID.equals(d.id));
        SRERole killer = role(x -> x.canUseKiller() && !x.isNeutrals(), "killer");
        killerRoleId = DailyRoleFacts.id(killer);
        List<DailyTaskDefinition> t = next.tasks;
        // PLAY_MATCH
        t.add(def("play_any", DailyTaskType.PLAY_MATCH, 5, d -> { }));
        t.add(def("play_win_killer", DailyTaskType.PLAY_MATCH, 1, d -> { d.matchResult = "win"; faction(d, DailyFactions.KILLER); }));
        t.add(def("play_lose", DailyTaskType.PLAY_MATCH, 1, d -> d.matchResult = "lose"));
        t.add(def("play_survived", DailyTaskType.PLAY_MATCH, 1, d -> d.matchResult = "survived"));
        t.add(def("play_elim", DailyTaskType.PLAY_MATCH, 1, d -> d.matchResult = "eliminated"));
        t.add(def("play_as_civ", DailyTaskType.PLAY_MATCH, 1, d -> faction(d, DailyFactions.CIVILIAN)));
        t.add(def("play_as_role", DailyTaskType.PLAY_MATCH, 1, d -> { d.roleMode = "role"; d.roles.add(killerRoleId); }));
        // KILL
        t.add(def("kill_any", DailyTaskType.KILL, 10, d -> { }));
        t.add(def("kill_knife", DailyTaskType.KILL, 10, d -> d.deathReasons.add("starrailexpress:knife_stab")));
        t.add(def("kill_enemy", DailyTaskType.KILL, 10, d -> d.victimMode = "enemy"));
        t.add(def("kill_victim_killer", DailyTaskType.KILL, 10, d -> { d.victimMode = "faction"; d.victimFactions.add(DailyFactions.KILLER); }));
        t.add(def("kill_as_civ", DailyTaskType.KILL, 10, d -> faction(d, DailyFactions.CIVILIAN)));
        t.add(def("kill_single", DailyTaskType.KILL, 2, d -> d.singleMatch = true));
        // SURVIVE_TIME
        t.add(def("surv", DailyTaskType.SURVIVE_TIME, 1, d -> { }));
        t.add(def("surv_single", DailyTaskType.SURVIVE_TIME, 1, d -> d.singleMatch = true));
        t.add(def("surv_as_civ", DailyTaskType.SURVIVE_TIME, 1, d -> faction(d, DailyFactions.CIVILIAN)));
        // FINISH_TASK
        t.add(def("quest_any", DailyTaskType.FINISH_TASK, 10, d -> { }));
        t.add(def("quest_eat", DailyTaskType.FINISH_TASK, 10, d -> d.quests.add("eat")));
        // USE_ITEM / SHOP_BUY / SHOP_SPEND / USE_SKILL
        t.add(def("item_any", DailyTaskType.USE_ITEM, 10, d -> { }));
        t.add(def("item_knife", DailyTaskType.USE_ITEM, 10, d -> d.items.add("starrailexpress:knife")));
        t.add(def("buy_any", DailyTaskType.SHOP_BUY, 10, d -> { }));
        t.add(def("buy_grenade", DailyTaskType.SHOP_BUY, 10, d -> d.items.add("starrailexpress:grenade")));
        t.add(def("spend", DailyTaskType.SHOP_SPEND, 1000, d -> { }));
        t.add(def("skill", DailyTaskType.USE_SKILL, 10, d -> { }));
        // SPECIAL_ACTION: one task per action id plus an unfiltered one
        t.add(def("special_any", DailyTaskType.SPECIAL_ACTION, 20, d -> { }));
        for (String action : List.of("gun_hit", "bomb_defuse", "bomb_detonate", "trap_triggered", "disguise",
                "door_pry", "door_seal", "rope_pull")) {
            t.add(def(action, DailyTaskType.SPECIAL_ACTION, 5, d -> d.actions.add(action)));
        }
        // START_MEETING
        t.add(def("meeting", DailyTaskType.START_MEETING, 1, d -> { }));
        // OPEN_CRATE
        t.add(def("crate_any", DailyTaskType.OPEN_CRATE, 10, d -> { }));
        t.add(def("crate_woodland", DailyTaskType.OPEN_CRATE, 10, d -> d.crates.add("woodland")));
        t.add(def("crate_cobalt", DailyTaskType.OPEN_CRATE, 10, d -> d.crates.add("cobalt")));
        // USE_CARD
        t.add(def("card_any", DailyTaskType.USE_CARD, 10, d -> { }));
        t.add(def("card_self", DailyTaskType.USE_CARD, 10, d -> d.cards.add("self_select")));
        t.add(def("card_lb", DailyTaskType.USE_CARD, 10, d -> d.cards.add("limit_break")));
        t.add(def("card_civ", DailyTaskType.USE_CARD, 10, d -> d.cards.add("civilian")));
        t.add(def("card_killer", DailyTaskType.USE_CARD, 10, d -> d.cards.add("killer")));
        // CLAIM_TASKS
        t.add(def("claimer", DailyTaskType.CLAIM_TASKS, 2, d -> { }));
        // Disabled task never counts
        t.add(def("disabled_kill", DailyTaskType.KILL, 10, d -> d.enabled = false));
        return next;
    }

    private void verifyProgress() {
        // LOGIN (default daily_login, counted on join)
        expect("daily_login", 1);
        // PLAY_MATCH: won as killer, alive at the end
        expect("play_any", 1);
        expect("play_win_killer", 1);
        expect("play_lose", 0);
        expect("play_survived", 1);
        expect("play_elim", 0);
        expect("play_as_civ", 0);
        expect("play_as_role", 1);
        // KILL: civ(knife) + civ(revolver) + killer teammate(knife)
        expect("kill_any", 3);
        expect("kill_knife", 2);
        expect("kill_enemy", 2);
        expect("kill_victim_killer", 1);
        expect("kill_as_civ", 0);
        expect("kill_single", 2);
        expect("disabled_kill", 0);
        // SURVIVE_TIME
        expect("surv", 1);
        expect("surv_single", 1);
        expect("surv_as_civ", 0);
        // FINISH_TASK: eat, sleep, eat
        expect("quest_any", 3);
        expect("quest_eat", 2);
        // items / shop / skill
        expect("item_any", 2);
        expect("item_knife", 1);
        expect("buy_any", 2);
        expect("buy_grenade", 1);
        expect("spend", 350);
        expect("skill", 1);
        // SPECIAL_ACTION
        expect("special_any", 8);
        for (String action : List.of("gun_hit", "bomb_defuse", "bomb_detonate", "trap_triggered", "disguise",
                "door_pry", "door_seal", "rope_pull")) expect(action, 1);
        // START_MEETING
        expect("meeting", 1);
        // OPEN_CRATE
        expect("crate_any", 2);
        expect("crate_woodland", 2);
        expect("crate_cobalt", 0);
        // USE_CARD: self_select (before match), limit_break + civilian (after)
        expect("card_any", 3);
        expect("card_self", 1);
        expect("card_lb", 1);
        expect("card_civ", 1);
        expect("card_killer", 0);
        // CLAIM_TASKS before any claim
        expect("claimer", 0);
    }

    private void expect(String id, int value) {
        Integer actual = progress.get(id);
        boolean ok = actual != null && actual == value;
        checks.add((ok ? "PASS " : "FAIL ") + id + " expected=" + value + " actual=" + actual);
        HabiLotteryMod.LOGGER.info("DAILY_MATCH {} {} expected={} actual={}", ok ? "PASS" : "FAIL", id, value, actual);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Integer> readProgress(ServerPlayer p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (DailyTaskDefinition d : DailyTaskConfigService.current().tasks) {
            out.put(d.id, HabiDailyTaskApi.progress(p, DailyTaskConfigService.boardId(d.id)));
        }
        return out;
    }

    private static Map<String, Object> snapshot(ServerPlayer p) {
        var data = PlayerLotteryStore.get().getOrLoad(p);
        return Map.of("apples", data.greenApples,
                "claimer", HabiDailyTaskApi.progress(p, DailyTaskConfigService.boardId("claimer")),
                "gun_hit_claimed", HabiDailyTaskApi.claimed(p, DailyTaskConfigService.boardId("gun_hit")));
    }

    private static void faction(DailyTaskDefinition d, String faction) {
        d.roleMode = "faction";
        d.factions.add(faction);
    }

    private static DailyTaskDefinition def(String id, DailyTaskType type, int target, Consumer<DailyTaskDefinition> edit) {
        DailyTaskDefinition t = new DailyTaskDefinition();
        t.id = id; t.title = id; t.type = type.id(); t.target = target;
        t.rewards.add(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 5));
        edit.accept(t);
        return t;
    }

    private static SRERole role(java.util.function.Predicate<SRERole> test, String preferredPath) {
        for (SRERole r : TMMRoles.ROLES.values()) if (r.identifier().getPath().equals(preferredPath) && test.test(r)) return r;
        return TMMRoles.ROLES.values().stream().filter(test).findFirst().orElseThrow();
    }

    private static String selfSelectRole() {
        for (SRERole r : com.habitrain.lottery.card.CardUseService.listCandidates(FactionCardType.NONE)) {
            if (r.identifier().getPath().equals("civilian")) return r.identifier().toString();
        }
        var all = com.habitrain.lottery.card.CardUseService.listCandidates(FactionCardType.NONE);
        return all.isEmpty() ? "starrailexpress:civilian" : all.getFirst().identifier().toString();
    }

    private static void sendOpen() {
        ClientPlayNetworking.send(new CrateNetwork.OpenRequestC2S("woodland", CrateService.keyItemId("woodland"),
                UUID.randomUUID().toString()));
    }

    private static ServerPlayer me(Minecraft mc) {
        return mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
    }

    private static SREGameWorldComponent.GameStatus status(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        return SREGameWorldComponent.KEY.get(server.overworld()).getGameStatus();
    }

    private static <T> CompletableFuture<T> server(Minecraft mc, Callable<T> task) {
        return mc.getSingleplayerServer().submit(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void next(int delay) { phase++; wait = delay; }

    private void shot(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(out.resolve(name + ".png"));
        }
    }

    private void check(boolean ok, String label) {
        checks.add((ok ? "PASS " : "FAIL ") + label);
        HabiLotteryMod.LOGGER.info("DAILY_MATCH {} {}", ok ? "PASS" : "FAIL", label);
        if (!ok) throw new IllegalStateException("check failed: " + label);
    }

    private void finish(Minecraft mc) {
        phase = -1;
        try {
            if (out != null) Files.write(out.resolve("checks.txt"), checks);
        } catch (Exception ignored) { }
        mc.stop();
    }

    /** Test-only one-player mode: the smoke player is the killer; everything else is upstream. */
    static final class SoloMode extends GameMode {
        SoloMode() {
            super(MODE_ID, 10, 1);
        }

        @Override
        public void initializeGame(ServerLevel serverWorld, SREGameWorldComponent game, List<ServerPlayer> players) {
            SRERole killer = role(x -> x.canUseKiller() && !x.isNeutrals(), "killer");
            for (ServerPlayer player : players) game.addRole(player, killer);
        }

        @Override
        public boolean shouldRecordPlayerStats() { return true; }

        @Override
        public boolean canHaveMeeting() { return true; }

        // A flat test world has no train map: the play-area / water checks would kill the player
        // ("fell out of train") on the first tick.
        @Override
        public boolean enablePlayAreaDetections() { return false; }

        @Override
        public boolean enableEnvironmentDetection() { return false; }
    }
}
