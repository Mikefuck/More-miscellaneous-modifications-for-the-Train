package com.habitrain.lottery.daily.config;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.replay.ReplayTimelineEvent;
import io.wifi.starrailexpress.api.replay.event.ReplayEventRecordedCallback;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.MeetingStartEvent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import io.wifi.starrailexpress.event.OnRevolverUsed;
import io.wifi.starrailexpress.game.GameUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns gameplay into daily-task progress. Every hook reduces what happened to a
 * {@link DailyTaskEvent} and {@link #record} advances each of the player's active tasks that
 * {@link DailyTaskEvent#matches matches} it, in one durable write.
 *
 * <h2>Sources</h2>
 * <ul>
 *   <li>confirmed kills: {@code OnPlayerDeathWithKiller} (after shields / kill redirection);</li>
 *   <li>match results: {@code OnGameEnd} snapshot + {@code ProgressionDataManager.onRoundSettled}
 *       (mixin) for the authoritative per-player win, settled on the next server tick;</li>
 *   <li>in-match tasks: {@code ProgressionDataManager.onRoundQuestFinished} (mixin);</li>
 *   <li>shop, items, skills and special actions: the match replay timeline callback;</li>
 *   <li>revolver hits and meetings: {@code OnRevolverUsed} / {@code MeetingStartEvent};</li>
 *   <li>survival: sampled once per second while a player is alive in an ACTIVE match.</li>
 * </ul>
 * All state is server-thread only.
 */
public final class DailyTaskTracker {
    private static boolean registered;
    private static int tickCounter;

    /** Per-match counters of single-match tasks: player -> task id -> units this match. */
    private static final Map<UUID, Map<String, Integer>> MATCH_PROGRESS = new HashMap<>();
    /** Sub-minute survival seconds for cumulative survival tasks: player -> task id -> seconds. */
    private static final Map<UUID, Map<String, Integer>> SURVIVAL_BUFFER = new HashMap<>();
    /** Players eliminated in the current match (revival removes them again). */
    private static final Set<UUID> ELIMINATED = new HashSet<>();
    /** Final roles captured at game end, settled on the next tick once wins are known. */
    private static final Map<UUID, SRERole> PENDING_SETTLEMENT = new LinkedHashMap<>();
    private static final Map<UUID, Boolean> SETTLED_WINS = new HashMap<>();
    private static boolean settlementPending;
    /** Crate open request ids already counted (bounded, insertion order). */
    private static final Set<String> COUNTED_OPENS = new java.util.LinkedHashSet<>();

    private DailyTaskTracker() { }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        OnGameTrueStarted.EVENT.register(level -> safe("match start", DailyTaskTracker::resetMatch));
        OnGameEnd.EVENT.register((level, game) -> safe("match end", () -> {
            PENDING_SETTLEMENT.clear();
            SETTLED_WINS.clear();
            if (game != null) PENDING_SETTLEMENT.putAll(game.getRoles());
            settlementPending = !PENDING_SETTLEMENT.isEmpty();
        }));
        OnPlayerDeathWithKiller.EVENT.register((victim, killer, reason) -> safe("kill", () -> onDeath(victim, killer, reason)));
        OnRevolverUsed.EVENT.register((shooter, target) -> safe("revolver", () -> {
            if (target != null && target != shooter) recordInMatch(shooter, DailyTaskType.SPECIAL_ACTION, 1, "gun_hit");
        }));
        MeetingStartEvent.EVENT.register((level, reporter) -> safe("meeting",
                () -> recordInMatch(reporter, DailyTaskType.START_MEETING, 1, "meeting")));
        ReplayEventRecordedCallback.EVENT.register((event, snapshot) -> safe("replay", () -> onReplay(event)));
        ServerTickEvents.END_SERVER_TICK.register(server -> safe("tick", () -> onTick(server)));
        HabiDailyTaskApi.addClaimListener((player, taskId) -> safe("claim", () -> onClaim(player, taskId)));
    }

    // ------------------------------------------------------------------ hooks

    public static void onLogin(ServerPlayer player) {
        safe("login", () -> record(player, DailyTaskEvent.simple(DailyTaskType.LOGIN, 1, null)));
    }

    /** Mixin: {@code ProgressionDataManager.onRoundQuestFinished(ServerPlayer, String)}. */
    public static void onQuestFinished(ServerPlayer player, String quest) {
        safe("quest", () -> recordInMatch(player, DailyTaskType.FINISH_TASK, 1, quest));
    }

    /** Mixin: {@code ProgressionDataManager.onRoundSettled} — the authoritative win flag. */
    public static void onRoundSettled(ServerPlayer player, SRERole role, boolean winner) {
        if (player == null) return;
        SETTLED_WINS.put(player.getUUID(), winner);
        if (role != null) PENDING_SETTLEMENT.putIfAbsent(player.getUUID(), role);
        settlementPending = true;
    }

    /** Called for every successful open reply; a replayed request id (retry after lag) counts once. */
    public static void onCrateOpened(ServerPlayer player, String crateId, String openId) {
        if (openId != null && !openId.isBlank() && !COUNTED_OPENS.add(openId)) return;
        while (COUNTED_OPENS.size() > 512) COUNTED_OPENS.remove(COUNTED_OPENS.iterator().next());
        safe("crate", () -> record(player, DailyTaskEvent.simple(DailyTaskType.OPEN_CRATE, 1, crateId)));
    }

    /** {@code kind}: a faction card type, {@code self_select} or {@code limit_break}. */
    public static void onCardUsed(ServerPlayer player, String kind) {
        safe("card", () -> record(player, DailyTaskEvent.simple(DailyTaskType.USE_CARD, 1, kind)));
    }

    private static void onDeath(Player victim, Player killer, ResourceLocation reason) {
        if (victim == null) return;
        ELIMINATED.add(victim.getUUID());
        if (!(killer instanceof ServerPlayer serverKiller) || killer == victim) return;
        SRERole killerRole = roleOf(serverKiller);
        if (killerRole == null) return;
        SRERole victimRole = roleOf(victim);
        record(serverKiller, new DailyTaskEvent(DailyTaskType.KILL, 1, DailyRoleFacts.id(killerRole),
                DailyRoleFacts.faction(killerRole), reason == null ? null : reason.toString(),
                DailyRoleFacts.faction(victimRole), false, false));
    }

    private static void onReplay(ReplayTimelineEvent event) {
        if (event == null || event.type() == null) return;
        UUID actor = event.actor() != null ? event.actor().uuid() : parse(event.data().get("sourcePlayer"));
        if (actor == null) return;
        String item = event.data().getOrDefault("itemUsed", "");
        switch (event.type()) {
            case PLAYER_REVIVAL -> ELIMINATED.remove(actor);
            case STORE_BUY -> {
                ServerPlayer player = online(actor);
                int cut = item.lastIndexOf(':');
                String itemId = cut > item.indexOf(':') ? item.substring(0, cut) : item;
                recordInMatch(player, DailyTaskType.SHOP_BUY, 1, itemId);
                int price = parseInt(event.data().get("message"));
                if (price > 0) recordInMatch(player, DailyTaskType.SHOP_SPEND, price, itemId);
            }
            case ITEM_USED -> recordInMatch(online(actor), DailyTaskType.USE_ITEM, 1, item);
            case SKILL_RELEASE -> recordInMatch(online(actor), DailyTaskType.USE_SKILL, 1, item);
            case BOMB_DEFUSE -> recordInMatch(online(actor), DailyTaskType.SPECIAL_ACTION, 1, "bomb_defuse");
            case BOMB_DETONATE -> recordInMatch(online(actor), DailyTaskType.SPECIAL_ACTION, 1, "bomb_detonate");
            case TRAP_TRIGGERED -> recordInMatch(online(actor), DailyTaskType.SPECIAL_ACTION, 1, "trap_triggered");
            case DISGUISE -> recordInMatch(online(actor), DailyTaskType.SPECIAL_ACTION, 1, "disguise");
            case DOOR_PRY -> recordInMatch(online(actor), DailyTaskType.SPECIAL_ACTION, 1, "door_pry");
            case DOOR_SEAL -> recordInMatch(online(actor), DailyTaskType.SPECIAL_ACTION, 1, "door_seal");
            case ROPE_PULL -> recordInMatch(online(actor), DailyTaskType.SPECIAL_ACTION, 1, "rope_pull");
            default -> { }
        }
    }

    private static void onClaim(ServerPlayer player, ResourceLocation taskId) {
        DailyTaskDefinition claimed = definition(taskId);
        // A "claim N tasks" task never counts itself or another meta task.
        if (claimed != null && claimed.taskType() == DailyTaskType.CLAIM_TASKS) return;
        record(player, DailyTaskEvent.simple(DailyTaskType.CLAIM_TASKS, 1, taskId.toString()));
    }

    private static void onTick(MinecraftServer server) {
        if (settlementPending) {
            settlementPending = false;
            settleMatch(server);
        }
        if (++tickCounter < 20) return;
        tickCounter = 0;
        sampleSurvival(server);
    }

    /** One survival sample (= one second) for every player alive in an ACTIVE match. */
    public static void sampleSurvival(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            SRERole role = activeRole(player);
            if (role == null || !GameUtils.isPlayerAliveAndSurvival(player)) continue;
            record(player, DailyTaskEvent.inMatch(DailyTaskType.SURVIVE_TIME, 1,
                    DailyRoleFacts.id(role), DailyRoleFacts.faction(role), null));
        }
    }

    private static void settleMatch(MinecraftServer server) {
        try {
            for (Map.Entry<UUID, SRERole> entry : PENDING_SETTLEMENT.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) continue;
                SRERole role = entry.getValue();
                boolean won = SETTLED_WINS.getOrDefault(entry.getKey(), false);
                boolean survived = !ELIMINATED.contains(entry.getKey());
                record(player, new DailyTaskEvent(DailyTaskType.PLAY_MATCH, 1, DailyRoleFacts.id(role),
                        DailyRoleFacts.faction(role), null, null, won, survived));
            }
        } finally {
            resetMatch();
        }
    }

    private static void resetMatch() {
        MATCH_PROGRESS.clear();
        ELIMINATED.clear();
        PENDING_SETTLEMENT.clear();
        SETTLED_WINS.clear();
        settlementPending = false;
    }

    // ------------------------------------------------------------------ progress

    private static void recordInMatch(ServerPlayer player, DailyTaskType type, int amount, String detail) {
        if (player == null) return;
        SRERole role = roleOf(player);
        if (role == null) return;
        record(player, DailyTaskEvent.inMatch(type, amount, DailyRoleFacts.id(role), DailyRoleFacts.faction(role), detail));
    }

    /** Advances every active task of {@code player} that counts {@code event}. Public for tests / extensions. */
    public static void record(ServerPlayer player, DailyTaskEvent event) {
        if (player == null || event == null || event.amount() <= 0) return;
        List<DailyTaskDefinition> active = DailyTaskConfigService.activeFor(player);
        if (active.isEmpty()) return;
        Map<ResourceLocation, Integer> deltas = new LinkedHashMap<>();
        for (DailyTaskDefinition task : active) {
            if (!event.matches(task)) continue;
            ResourceLocation id = DailyTaskConfigService.boardId(task.id);
            if (HabiDailyTaskApi.claimed(player, id)) continue;
            int progress = HabiDailyTaskApi.progress(player, id);
            if (progress >= task.target) continue;
            int delta = progressDelta(player.getUUID(), task, event, progress);
            if (delta > 0) deltas.put(id, delta);
        }
        if (!deltas.isEmpty() && !HabiDailyTaskApi.advanceAll(player, deltas)) {
            HabiLotteryMod.LOGGER.warn("Daily task progress for {} could not be saved: {}",
                    player.getGameProfile().getName(), deltas.keySet());
        }
    }

    /**
     * Units to add to today's progress. Survival counts seconds and converts to whole minutes;
     * single-match tasks report the best single match, so progress only rises when the current
     * match beats the previous best.
     */
    static int progressDelta(UUID player, DailyTaskDefinition task, DailyTaskEvent event, int progress) {
        boolean survival = task.taskType() == DailyTaskType.SURVIVE_TIME;
        if (task.singleMatch) {
            int inMatch = MATCH_PROGRESS.computeIfAbsent(player, k -> new HashMap<>())
                    .merge(task.id, event.amount(), Integer::sum);
            int units = survival ? inMatch / 60 : inMatch;
            return Math.max(0, Math.min(task.target, units) - progress);
        }
        if (!survival) return event.amount();
        Map<String, Integer> buffer = SURVIVAL_BUFFER.computeIfAbsent(player, k -> new HashMap<>());
        int seconds = buffer.merge(task.id, event.amount(), Integer::sum);
        int minutes = seconds / 60;
        buffer.put(task.id, seconds - minutes * 60);
        return minutes;
    }

    // ------------------------------------------------------------------ helpers

    private static SRERole roleOf(Player player) {
        if (player == null) return null;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.level());
            return game == null || !game.isRunning() ? null : game.getRole(player);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** The role of a player inside an ACTIVE match (not while starting or stopping). */
    private static SRERole activeRole(ServerPlayer player) {
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.level());
            if (game == null || game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) return null;
            return game.getRole(player);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static DailyTaskDefinition definition(ResourceLocation id) {
        if (id == null || !HabiLotteryMod.MOD_ID.equals(id.getNamespace())) return null;
        return DailyTaskConfigService.current().find(id.getPath());
    }

    private static ServerPlayer online(UUID uuid) {
        MinecraftServer server = HabiLotteryMod.getServer();
        return server == null || uuid == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    private static UUID parse(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    /** Forgets per-player buffers (logout); match counters survive a reconnect within the match. */
    public static void forget(UUID player) {
        SURVIVAL_BUFFER.remove(player);
    }

    private static void safe(String label, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            HabiLotteryMod.LOGGER.warn("Daily task tracker hook '{}' failed", label, failure);
        }
    }
}
