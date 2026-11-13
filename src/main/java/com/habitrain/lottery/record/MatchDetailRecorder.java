package com.habitrain.lottery.record;

import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.replay.GameReplayData;
import io.wifi.starrailexpress.api.replay.GameReplayManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.event.OnGameStarted;
import net.exmo.sre.record.MatchRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks in-match player economy flows, task completions, shop purchases, and per-player summaries
 * into hidden timeline events for {@link MatchRecord}.
 */
public final class MatchDetailRecorder {

    public static final String ECON_SUMMARY_TYPE = "ECON_SUMMARY";
    private static final int MAX_SCOPE_DEPTH = 32;

    public enum CoinReason {
        NATURAL_ECONOMY,
        PASSIVE_MAGNATE,
        PASSIVE_ACCOUNTANT,
        PASSIVE_AVARICIOUS,
        BIG_EATER,
        STINGY_REFUND,
        MODIFIER_EFFECT,
        GOLD_PICKUP,
        SKILL_OR_MODIFIER
    }

    public interface EventSink {
        void emitHiddenEvent(UUID playerId, Component text);

        Component formatPlayer(UUID playerId);

        Component formatTaskName(String quest);

        default String serializeComponent(Component text, RegistryAccess access) {
            return Component.Serializer.toJson(text, access);
        }
    }

    private static volatile boolean registered = false;
    private static final Ledger LIVE_LEDGER = new Ledger(new LiveEventSink());
    private static final ThreadLocal<Integer> INIT_SUPPRESS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private MatchDetailRecorder() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        OnGameStarted.EVENT.register(MatchDetailRecorder::onGameStarted);
    }

    public static void reset() {
        INIT_SUPPRESS_DEPTH.remove();
        LIVE_LEDGER.reset();
    }

    public static void onGameStarted(ServerLevel serverWorld) {
        if (serverWorld == null || serverWorld.isClientSide) {
            return;
        }
        LIVE_LEDGER.reset();
        LIVE_LEDGER.beginTracking();
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.maybeGet(serverWorld).orElse(null);
        for (ServerPlayer player : serverWorld.players()) {
            String roleId = resolveRoleId(gameWorld, player.getUUID());
            if (roleId == null) continue;
            if (SRE.REPLAY_MANAGER != null) {
                SRE.REPLAY_MANAGER.recordPlayerName(player);
            }
            SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.maybeGet(player).orElse(null);
            int balance = shop != null ? shop.balance : 0;
            LIVE_LEDGER.recordInitialBalance(player.getUUID(), player.getName().getString(), roleId, balance);
        }
    }

    public static void beginInitSuppress(Player player) {
        if (player == null || player.level() == null || player.level().isClientSide) {
            return;
        }
        INIT_SUPPRESS_DEPTH.set(INIT_SUPPRESS_DEPTH.get() + 1);
    }

    public static void endInitSuppress(Player player, int balanceAfterInit) {
        if (player == null || player.level() == null || player.level().isClientSide) {
            return;
        }
        int depth = INIT_SUPPRESS_DEPTH.get();
        if (depth <= 1) {
            INIT_SUPPRESS_DEPTH.remove();
        } else {
            INIT_SUPPRESS_DEPTH.set(depth - 1);
        }
        LIVE_LEDGER.syncSuppressedBalance(player.getUUID(), balanceAfterInit);
    }

    public static void pushReason(CoinReason reason) {
        if (reason == null) {
            return;
        }
        LIVE_LEDGER.pushSimple(reason);
    }

    public static void pushNaturalEconomy() {
        pushReason(CoinReason.NATURAL_ECONOMY);
    }

    public static void popReason() {
        LIVE_LEDGER.popSimple();
    }

    public static void beginTaskScope(Player player, String quest) {
        if (player == null || player.level() == null || player.level().isClientSide) {
            return;
        }
        LIVE_LEDGER.beginTaskScope(player.getUUID(), quest, false);
    }

    public static void endTaskScope(Player player, String quest) {
        if (player == null || player.level() == null || player.level().isClientSide) {
            return;
        }
        LIVE_LEDGER.endTaskScope(player.getUUID(), quest, true);
    }

    public static void beginMinigameTaskScope(ServerPlayer player, String minigameId) {
        if (player == null || player.level() == null || player.level().isClientSide) {
            return;
        }
        LIVE_LEDGER.beginTaskScope(player.getUUID(), minigameId, true);
    }

    public static void endMinigameTaskScope(ServerPlayer player, String minigameId, boolean completed) {
        if (player == null || player.level() == null || player.level().isClientSide) {
            return;
        }
        LIVE_LEDGER.endTaskScope(player.getUUID(), minigameId, completed);
    }

    public static void beginKillScope(Player victim, Player killer) {
        if (victim == null || victim.level() == null || victim.level().isClientSide) {
            return;
        }
        LIVE_LEDGER.beginKillScope(victim.getUUID(), killer != null ? killer.getUUID() : null);
    }

    public static void endKillScope(Player victim, Player killer) {
        if (victim == null || victim.level() == null || victim.level().isClientSide) {
            return;
        }
        LIVE_LEDGER.endKillScope(victim.getUUID(), killer != null ? killer.getUUID() : null);
    }

    public static void onStoreBuyRecorded(UUID buyerUuid, int price) {
        if (buyerUuid == null || price < 0 || !LIVE_LEDGER.isTracking()) {
            return;
        }
        LIVE_LEDGER.onStoreBuy(buyerUuid, price);
    }

    public static void onShopBalanceObserved(Player player, int currentBalance) {
        if (player == null || player.level() == null || player.level().isClientSide) {
            return;
        }
        if (INIT_SUPPRESS_DEPTH.get() > 0) {
            LIVE_LEDGER.syncSuppressedBalance(player.getUUID(), currentBalance);
            return;
        }
        if (!LIVE_LEDGER.isTracking()) {
            return;
        }
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.maybeGet(player.level()).orElse(null);
        if (gameWorld == null || !gameWorld.isRunning()) {
            return;
        }
        if (SRE.REPLAY_MANAGER == null || SRE.REPLAY_MANAGER.currentReplayData == null) {
            return;
        }
        String roleId = resolveRoleId(gameWorld, player.getUUID());
        if (roleId == null && LIVE_LEDGER.snapshot(player.getUUID()) == null) return;
        LIVE_LEDGER.ensurePlayerIdentity(
                player.getUUID(),
                player.getName().getString(),
                roleId
        );
        LIVE_LEDGER.onBalanceObserved(player.getUUID(), currentBalance);
    }

    /**
     * Flushes any un-synced final player balances before {@code RoleMethodDispatcher.onEndGame}
     * resets {@code SREPlayerShopComponent}, and freezes tracking so clearing components to 0
     * does not emit spurious negative coin events.
     */
    public static void flushAndFreezeOnEndGame(ServerLevel serverWorld) {
        if (serverWorld == null || !LIVE_LEDGER.isTracking()) {
            return;
        }
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.maybeGet(serverWorld).orElse(null);
        for (ServerPlayer player : serverWorld.players()) {
            if (LIVE_LEDGER.snapshot(player.getUUID()) == null) continue;
            SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.maybeGet(player).orElse(null);
            if (shop != null) {
                LIVE_LEDGER.ensurePlayerIdentity(
                        player.getUUID(),
                        player.getName().getString(),
                        resolveRoleId(gameWorld, player.getUUID())
                );
                LIVE_LEDGER.onBalanceObserved(player.getUUID(), shop.balance);
            }
        }
        LIVE_LEDGER.stopTrackingOnly();
    }

    public static void beforeBuildMatchRecord(ServerLevel serverWorld) {
        if (serverWorld != null && LIVE_LEDGER.isTracking()) {
            flushAndFreezeOnEndGame(serverWorld);
        }
    }

    public static void enrichBuiltMatchRecord(
            GameReplayData data,
            MatchRecord record,
            RegistryAccess registryAccess
    ) {
        if (record == null) {
            LIVE_LEDGER.clearAccumulators();
            return;
        }
        try {
            if (data != null && data.getTimeline() != null && record.players != null) {
                applySurvivalState(data, record.players);
            }
            LIVE_LEDGER.appendPlayerSummaryEvents(record, registryAccess);
        } finally {
            LIVE_LEDGER.clearAccumulators();
        }
    }

    private static void applySurvivalState(GameReplayData data, List<MatchRecord.MatchPlayer> players) {
        Set<String> deadIds = new HashSet<>();
        for (GameReplayData.ReplayEvent event : data.getTimeline()) {
            if (event == null || event.getType() == null) {
                continue;
            }
            if (event.getType() == GameReplayData.EventType.PLAYER_KILL && event.getTargetPlayer() != null) {
                deadIds.add(event.getTargetPlayer().toString());
            } else if (event.getType() == GameReplayData.EventType.PLAYER_REVIVAL && event.getSourcePlayer() != null) {
                deadIds.remove(event.getSourcePlayer().toString());
            }
        }
        for (MatchRecord.MatchPlayer player : players) {
            if (player != null && player.uuid != null) {
                player.alive = !deadIds.contains(player.uuid);
            }
        }
    }

    private static String resolveRoleId(SREGameWorldComponent gameWorld, UUID playerId) {
        if (gameWorld == null || playerId == null) {
            return null;
        }
        SRERole role = gameWorld.getRole(playerId);
        return role != null ? role.identifier().toString() : null;
    }

    private static final class LiveEventSink implements EventSink {
        @Override
        public void emitHiddenEvent(UUID playerId, Component text) {
            if (text == null || SRE.REPLAY_MANAGER == null || SRE.REPLAY_MANAGER.currentReplayData == null) {
                return;
            }
            SRE.REPLAY_MANAGER.recordCustomEvent(text, true);
        }

        @Override
        public Component formatPlayer(UUID playerId) {
            GameReplayManager manager = SRE.REPLAY_MANAGER;
            GameReplayData data = manager != null ? manager.currentReplayData : null;
            return MatchReplayEventFormatter.formatPlayerWithRole(playerId, manager, data);
        }

        @Override
        public Component formatTaskName(String quest) {
            return MatchReplayEventFormatter.resolveTaskDisplayName(quest);
        }
    }

    /**
     * Stateful coin attribution engine decoupled from Minecraft server singletons so unit tests can
     * verify every economy, task, minigame, kill, pickup, and store purchase path directly.
     */
    public static final class Ledger {
        private final EventSink sink;
        private boolean tracking = false;
        private final Map<UUID, Integer> lastBalance = new HashMap<>();
        private final Map<UUID, PlayerAccumulator> accumulators = new LinkedHashMap<>();
        private final Deque<Object> scopeStack = new ArrayDeque<>();

        public Ledger(EventSink sink) {
            this.sink = sink;
        }

        public void reset() {
            tracking = false;
            lastBalance.clear();
            accumulators.clear();
            scopeStack.clear();
        }

        public void beginTracking() {
            tracking = true;
        }

        public boolean isTracking() {
            return tracking;
        }

        public void stopTrackingOnly() {
            tracking = false;
            scopeStack.clear();
        }

        public void clearAccumulators() {
            tracking = false;
            lastBalance.clear();
            accumulators.clear();
            scopeStack.clear();
        }

        public void ensurePlayerIdentity(UUID playerId, String name, String roleId) {
            if (playerId == null) {
                return;
            }
            PlayerAccumulator acc = accumulators.computeIfAbsent(playerId, ignored -> new PlayerAccumulator(playerId));
            if (name != null && !name.isBlank()) {
                acc.playerName = name;
            }
            if (roleId != null && !roleId.isBlank()) {
                acc.roleId = roleId;
            }
        }

        public void syncSuppressedBalance(UUID playerId, int balance) {
            if (!tracking || playerId == null) {
                return;
            }
            lastBalance.put(playerId, balance);
            PlayerAccumulator acc = accumulators.get(playerId);
            if (acc != null) {
                acc.finalBalance = balance;
            }
        }

        public void recordInitialBalance(UUID playerId, String name, String roleId, int initialBalance) {
            if (playerId == null) {
                return;
            }
            ensurePlayerIdentity(playerId, name, roleId);
            lastBalance.put(playerId, initialBalance);
            PlayerAccumulator acc = accumulators.get(playerId);
            acc.finalBalance = initialBalance;
            if (initialBalance > 0) {
                acc.initialCoins += initialBalance;
                sink.emitHiddenEvent(
                        playerId,
                        MatchReplayEventFormatter.translatable(
                                "habitrain_lottery.records.event.initial_coin",
                                "%1$s 获得初始资金 %2$s 金币",
                                sink.formatPlayer(playerId),
                                coinNumber(initialBalance)
                        ).withStyle(ChatFormatting.GRAY)
                );
            }
        }

        public void pushSimple(CoinReason reason) {
            if (reason == null || scopeStack.size() >= MAX_SCOPE_DEPTH) {
                return;
            }
            scopeStack.push(reason);
        }

        public void popSimple() {
            if (!scopeStack.isEmpty() && scopeStack.peek() instanceof CoinReason) {
                scopeStack.pop();
            }
        }

        public void beginTaskScope(UUID completerId, String quest, boolean minigame) {
            if (!tracking || completerId == null || scopeStack.size() >= MAX_SCOPE_DEPTH) {
                return;
            }
            scopeStack.push(new TaskScopeFrame(completerId, quest, minigame));
        }

        public void endTaskScope(UUID completerId, String quest, boolean completed) {
            TaskScopeFrame frame = popMatchingTaskFrame(completerId, quest);
            if (frame == null || !tracking) {
                return;
            }
            PlayerAccumulator completerAcc = accumulators.computeIfAbsent(completerId, ignored -> new PlayerAccumulator(completerId));
            int completerDelta = frame.completerDelta;
            if (completed || completerDelta != 0) {
                completerAcc.taskCount++;
                if (completerDelta > 0) {
                    completerAcc.taskCoins += completerDelta;
                } else if (completerDelta < 0) {
                    completerAcc.otherSpentCoins += -completerDelta;
                }
                emitCompleterTaskEvent(frame, completerDelta);
            }
            for (Map.Entry<UUID, Integer> entry : frame.otherDeltas.entrySet()) {
                UUID otherId = entry.getKey();
                int delta = entry.getValue();
                if (delta == 0) {
                    continue;
                }
                PlayerAccumulator otherAcc = accumulators.computeIfAbsent(otherId, ignored -> new PlayerAccumulator(otherId));
                if (delta > 0) {
                    otherAcc.otherGainCoins += delta;
                    String key = (!frame.minigame && frame.completerDelta == 0)
                            ? "habitrain_lottery.records.event.killer_task_income"
                            : "habitrain_lottery.records.event.task_share_coin";
                    String fallback = (!frame.minigame && frame.completerDelta == 0)
                            ? "%1$s 因他人完成任务获得 %2$s 金币"
                            : "%1$s 在任务结算中获得 %2$s 金币";
                    sink.emitHiddenEvent(
                            otherId,
                            MatchReplayEventFormatter.translatable(
                                    key,
                                    fallback,
                                    sink.formatPlayer(otherId),
                                    coinNumber(delta)
                            ).withStyle(ChatFormatting.GRAY)
                    );
                } else {
                    otherAcc.otherSpentCoins += -delta;
                    sink.emitHiddenEvent(
                            otherId,
                            MatchReplayEventFormatter.translatable(
                                    "habitrain_lottery.records.event.skill_coin_spend",
                                    "%1$s 因技能/职业效果消耗 %2$s 金币",
                                    sink.formatPlayer(otherId),
                                    coinNumber(-delta)
                            ).withStyle(ChatFormatting.GRAY)
                    );
                }
            }
        }

        private void emitCompleterTaskEvent(TaskScopeFrame frame, int delta) {
            Component playerText = sink.formatPlayer(frame.completerId);
            Component taskText = sink.formatTaskName(frame.quest).copy().withStyle(ChatFormatting.AQUA);
            if (frame.minigame) {
                if (delta > 0) {
                    sink.emitHiddenEvent(
                            frame.completerId,
                            MatchReplayEventFormatter.translatable(
                                    "habitrain_lottery.records.event.minigame_coin",
                                    "%1$s 通过完成小游戏任务「%2$s」获得 %3$s 金币",
                                    playerText,
                                    taskText,
                                    coinNumber(delta)
                            ).withStyle(ChatFormatting.GRAY)
                    );
                } else {
                    sink.emitHiddenEvent(
                            frame.completerId,
                            MatchReplayEventFormatter.translatable(
                                    "habitrain_lottery.records.event.minigame_done",
                                    "%1$s 完成了小游戏任务「%2$s」",
                                    playerText,
                                    taskText
                            ).withStyle(ChatFormatting.GRAY)
                    );
                }
                return;
            }
            if (delta > 0) {
                sink.emitHiddenEvent(
                        frame.completerId,
                        MatchReplayEventFormatter.translatable(
                                "habitrain_lottery.records.event.task_coin",
                                "%1$s 通过做「%2$s」任务获得 %3$s 金币",
                                playerText,
                                taskText,
                                coinNumber(delta)
                        ).withStyle(ChatFormatting.GRAY)
                );
            } else if (delta == 0) {
                sink.emitHiddenEvent(
                        frame.completerId,
                        MatchReplayEventFormatter.translatable(
                                "habitrain_lottery.records.event.task_done",
                                "%1$s 完成了「%2$s」任务",
                                playerText,
                                taskText
                        ).withStyle(ChatFormatting.GRAY)
                );
            } else {
                sink.emitHiddenEvent(
                        frame.completerId,
                        MatchReplayEventFormatter.translatable(
                                "habitrain_lottery.records.event.task_cost",
                                "%1$s 完成「%2$s」任务并消耗 %3$s 金币",
                                playerText,
                                taskText,
                                coinNumber(-delta)
                        ).withStyle(ChatFormatting.GRAY)
                );
            }
        }

        public void beginKillScope(UUID victimId, UUID killerId) {
            if (!tracking || victimId == null || scopeStack.size() >= MAX_SCOPE_DEPTH) {
                return;
            }
            scopeStack.push(new KillScopeFrame(victimId, killerId));
        }

        public void endKillScope(UUID victimId, UUID killerId) {
            KillScopeFrame frame = popMatchingKillFrame(victimId);
            if (frame == null || !tracking) {
                return;
            }
            UUID resolvedKiller = killerId != null ? killerId : frame.killerId;
            for (Map.Entry<UUID, Integer> entry : frame.deltas.entrySet()) {
                UUID playerId = entry.getKey();
                int delta = entry.getValue();
                if (delta == 0) {
                    continue;
                }
                PlayerAccumulator acc = accumulators.computeIfAbsent(playerId, ignored -> new PlayerAccumulator(playerId));
                if (delta > 0) {
                    boolean isKiller = resolvedKiller != null && resolvedKiller.equals(playerId);
                    if (isKiller) {
                        acc.killAndPickupCoins += delta;
                        if (!victimId.equals(playerId)) {
                            sink.emitHiddenEvent(
                                    playerId,
                                    MatchReplayEventFormatter.translatable(
                                            "habitrain_lottery.records.event.kill_coin_target",
                                            "%1$s 通过击杀 %2$s 获得 %3$s 金币",
                                            sink.formatPlayer(playerId),
                                            sink.formatPlayer(victimId),
                                            coinNumber(delta)
                                    ).withStyle(ChatFormatting.GRAY)
                            );
                        } else {
                            sink.emitHiddenEvent(
                                    playerId,
                                    MatchReplayEventFormatter.translatable(
                                            "habitrain_lottery.records.event.kill_coin",
                                            "%1$s 通过击杀获得 %2$s 金币",
                                            sink.formatPlayer(playerId),
                                            coinNumber(delta)
                                    ).withStyle(ChatFormatting.GRAY)
                            );
                        }
                    } else {
                        acc.otherGainCoins += delta;
                        sink.emitHiddenEvent(
                                playerId,
                                MatchReplayEventFormatter.translatable(
                                        "habitrain_lottery.records.event.kill_bystander_coin",
                                        "%1$s 在死亡结算中获得 %2$s 金币",
                                        sink.formatPlayer(playerId),
                                        coinNumber(delta)
                                ).withStyle(ChatFormatting.GRAY)
                        );
                    }
                } else {
                    acc.otherSpentCoins += -delta;
                    sink.emitHiddenEvent(
                            playerId,
                            MatchReplayEventFormatter.translatable(
                                    "habitrain_lottery.records.event.kill_loss",
                                    "%1$s 在死亡结算中失去 %2$s 金币",
                                    sink.formatPlayer(playerId),
                                    coinNumber(-delta)
                            ).withStyle(ChatFormatting.GRAY)
                    );
                }
            }
        }

        public void onStoreBuy(UUID buyerId, int price) {
            if (!tracking || buyerId == null || price < 0) {
                return;
            }
            PlayerAccumulator acc = accumulators.computeIfAbsent(buyerId, ignored -> new PlayerAccumulator(buyerId));
            int prev = lastBalance.getOrDefault(buyerId, 0);
            int expectedAfterBuy = prev - price;
            lastBalance.put(buyerId, expectedAfterBuy);
            acc.shopBuyCount++;
            acc.shopSpentCoins += price;
            acc.finalBalance = expectedAfterBuy;
        }

        public void onBalanceObserved(UUID playerId, int currentBalance) {
            if (!tracking || playerId == null) {
                return;
            }
            int prev = lastBalance.getOrDefault(playerId, 0);
            int delta = currentBalance - prev;
            if (delta == 0) {
                return;
            }
            lastBalance.put(playerId, currentBalance);
            PlayerAccumulator acc = accumulators.computeIfAbsent(playerId, ignored -> new PlayerAccumulator(playerId));
            acc.finalBalance = currentBalance;

            Object top = scopeStack.peek();
            if (top instanceof TaskScopeFrame taskFrame) {
                taskFrame.recordDelta(playerId, delta);
                return;
            }
            if (top instanceof KillScopeFrame killFrame) {
                killFrame.recordDelta(playerId, delta);
                return;
            }

            CoinReason reason = (top instanceof CoinReason cr) ? cr : CoinReason.SKILL_OR_MODIFIER;
            applySimpleReasonDelta(playerId, acc, reason, delta);
        }

        private void applySimpleReasonDelta(UUID playerId, PlayerAccumulator acc, CoinReason reason, int delta) {
            if (delta > 0) {
                switch (reason) {
                    case NATURAL_ECONOMY -> {
                        acc.naturalCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.natural_coin",
                                "%1$s 通过自然经济获得 %2$s 金币", delta);
                    }
                    case PASSIVE_MAGNATE -> {
                        acc.naturalCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.passive_magnate",
                                "%1$s 通过自然经济（富豪）获得 %2$s 金币", delta);
                    }
                    case PASSIVE_ACCOUNTANT -> {
                        acc.naturalCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.passive_accountant",
                                "%1$s 通过自然经济（会计）获得 %2$s 金币", delta);
                    }
                    case PASSIVE_AVARICIOUS -> {
                        acc.naturalCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.passive_avaricious",
                                "%1$s 通过自然经济（聚财）获得 %2$s 金币", delta);
                    }
                    case BIG_EATER -> {
                        acc.otherGainCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.big_eater_coin",
                                "%1$s 通过词条「大胃王」获得 %2$s 金币", delta);
                    }
                    case STINGY_REFUND -> {
                        acc.otherGainCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.stingy_refund",
                                "%1$s 通过词条「吝啬」返还 %2$s 金币", delta);
                    }
                    case MODIFIER_EFFECT -> {
                        acc.otherGainCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.modifier_coin",
                                "%1$s 通过词条效果获得 %2$s 金币", delta);
                    }
                    case GOLD_PICKUP -> {
                        acc.killAndPickupCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.gold_pickup",
                                "%1$s 通过拾取金币获得 %2$s 金币", delta);
                    }
                    case SKILL_OR_MODIFIER -> {
                        acc.otherGainCoins += delta;
                        emitSimpleGain(playerId, "habitrain_lottery.records.event.skill_coin_gain",
                                "%1$s 通过技能/职业效果获得 %2$s 金币", delta);
                    }
                }
            } else {
                acc.otherSpentCoins += -delta;
                sink.emitHiddenEvent(
                        playerId,
                        MatchReplayEventFormatter.translatable(
                                "habitrain_lottery.records.event.skill_coin_spend",
                                "%1$s 因技能/职业效果消耗 %2$s 金币",
                                sink.formatPlayer(playerId),
                                coinNumber(-delta)
                        ).withStyle(ChatFormatting.GRAY)
                );
            }
        }

        private void emitSimpleGain(UUID playerId, String key, String fallback, int amount) {
            sink.emitHiddenEvent(
                    playerId,
                    MatchReplayEventFormatter.translatable(
                            key,
                            fallback,
                            sink.formatPlayer(playerId),
                            coinNumber(amount)
                    ).withStyle(ChatFormatting.GRAY)
            );
        }

        public void appendPlayerSummaryEvents(MatchRecord record, RegistryAccess registryAccess) {
            if (record == null || accumulators.isEmpty()) {
                return;
            }
            if (record.events == null) {
                record.events = new ArrayList<>();
            }
            RegistryAccess access = registryAccess != null ? registryAccess : RegistryAccess.EMPTY;

            Map<String, MatchRecord.MatchPlayer> byUuid = new HashMap<>();
            if (record.players != null) {
                for (MatchRecord.MatchPlayer mp : record.players) {
                    if (mp != null && mp.uuid != null) {
                        byUuid.put(mp.uuid, mp);
                    }
                }
            }

            List<PlayerAccumulator> ordered = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            if (record.players != null) {
                for (MatchRecord.MatchPlayer mp : record.players) {
                    if (mp == null || mp.uuid == null) {
                        continue;
                    }
                    try {
                        UUID uid = UUID.fromString(mp.uuid);
                        PlayerAccumulator acc = accumulators.get(uid);
                        if (acc != null) {
                            ordered.add(acc);
                            seen.add(uid);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            for (PlayerAccumulator acc : accumulators.values()) {
                if (!seen.contains(acc.playerId)) {
                    ordered.add(acc);
                }
            }

            for (PlayerAccumulator acc : ordered) {
                int totalGain = acc.initialCoins + acc.naturalCoins + acc.taskCoins
                        + acc.killAndPickupCoins + acc.otherGainCoins;
                if (totalGain == 0 && acc.taskCount == 0 && acc.shopBuyCount == 0
                        && acc.otherSpentCoins == 0 && acc.finalBalance == 0) {
                    continue;
                }
                MatchRecord.MatchPlayer mp = byUuid.get(acc.playerId.toString());
                if (mp != null && (acc.playerName == null || acc.playerName.isBlank()) && mp.name != null) {
                    acc.playerName = mp.name;
                }
                Component playerLabel = sink.formatPlayer(acc.playerId);
                Component summaryText = MatchReplayEventFormatter.translatable(
                        "habitrain_lottery.records.player_summary",
                        "%1$s：总获得 +%2$s 金币（初始 +%3$s · 自然 +%4$s · 任务×%5$s +%6$s · 击杀/拾取 +%7$s · 技能/其他 +%8$s）· 商店消费×%9$s -%10$s · 其他消耗 -%11$s · 结余 %12$s",
                        playerLabel,
                        numText(totalGain, ChatFormatting.GOLD),
                        numText(acc.initialCoins, ChatFormatting.YELLOW),
                        numText(acc.naturalCoins, ChatFormatting.YELLOW),
                        numText(acc.taskCount, ChatFormatting.AQUA),
                        numText(acc.taskCoins, ChatFormatting.YELLOW),
                        numText(acc.killAndPickupCoins, ChatFormatting.YELLOW),
                        numText(acc.otherGainCoins, ChatFormatting.YELLOW),
                        numText(acc.shopBuyCount, ChatFormatting.AQUA),
                        numText(acc.shopSpentCoins, ChatFormatting.RED),
                        numText(acc.otherSpentCoins, ChatFormatting.RED),
                        numText(acc.finalBalance, ChatFormatting.GREEN)
                ).withStyle(ChatFormatting.GRAY);

                MatchRecord.MatchEvent ev = new MatchRecord.MatchEvent();
                ev.type = ECON_SUMMARY_TYPE;
                ev.relativeTimestamp = 0L;
                ev.actorName = acc.playerName;
                ev.textJson = sink.serializeComponent(summaryText, access);
                ev.hidden = true;
                record.events.add(ev);
            }
        }

        private TaskScopeFrame popMatchingTaskFrame(UUID completerId, String quest) {
            for (Object frame : scopeStack) {
                if (frame instanceof TaskScopeFrame tf
                        && completerId.equals(tf.completerId)
                        && (quest == null || quest.equals(tf.quest))) {
                    scopeStack.remove(frame);
                    return tf;
                }
            }
            return null;
        }

        private KillScopeFrame popMatchingKillFrame(UUID victimId) {
            for (Object frame : scopeStack) {
                if (frame instanceof KillScopeFrame kf && victimId.equals(kf.victimId)) {
                    scopeStack.remove(frame);
                    return kf;
                }
            }
            return null;
        }

        public PlayerAccumulator snapshot(UUID playerId) {
            return accumulators.get(playerId);
        }

        private static Component coinNumber(int amount) {
            return Component.literal(String.valueOf(amount)).withStyle(ChatFormatting.GOLD);
        }

        private static Component numText(int value, ChatFormatting color) {
            return Component.literal(String.valueOf(value)).withStyle(color);
        }
    }

    public static final class PlayerAccumulator {
        public final UUID playerId;
        public String playerName;
        public String roleId;
        public int initialCoins;
        public int naturalCoins;
        public int taskCount;
        public int taskCoins;
        public int killAndPickupCoins;
        public int otherGainCoins;
        public int shopBuyCount;
        public int shopSpentCoins;
        public int otherSpentCoins;
        public int finalBalance;

        PlayerAccumulator(UUID playerId) {
            this.playerId = playerId;
        }
    }

    private static final class TaskScopeFrame {
        final UUID completerId;
        final String quest;
        final boolean minigame;
        int completerDelta;
        final Map<UUID, Integer> otherDeltas = new LinkedHashMap<>();

        TaskScopeFrame(UUID completerId, String quest, boolean minigame) {
            this.completerId = completerId;
            this.quest = quest;
            this.minigame = minigame;
        }

        void recordDelta(UUID playerId, int delta) {
            if (completerId.equals(playerId)) {
                completerDelta += delta;
            } else {
                otherDeltas.merge(playerId, delta, Integer::sum);
            }
        }
    }

    private static final class KillScopeFrame {
        final UUID victimId;
        final UUID killerId;
        final Map<UUID, Integer> deltas = new LinkedHashMap<>();

        KillScopeFrame(UUID victimId, UUID killerId) {
            this.victimId = victimId;
            this.killerId = killerId;
        }

        void recordDelta(UUID playerId, int delta) {
            deltas.merge(playerId, delta, Integer::sum);
        }
    }
}
