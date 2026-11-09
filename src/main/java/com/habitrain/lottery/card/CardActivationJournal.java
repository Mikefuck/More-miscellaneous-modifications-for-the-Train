package com.habitrain.lottery.card;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.DailyFactionCardService;
import com.habitrain.lottery.backpack.DailySelfSelectService;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/**
 * Durable activation ordering: account reservation + quota, then receipted backpack debit.
 * Restart refunds only a proven debit. The reservation remains until both refund stores commit.
 * Mike's recovery policy refunds an unsettled reservation even if a match may already have used it.
 */
public final class CardActivationJournal {
    private CardActivationJournal() { }

    public static boolean hasPending(UUID player) {
        if (player == null || !WorldLotteryPaths.ready()) return false;
        var data = PlayerLotteryStore.get().getOrLoad(player);
        return data.pendingCardUses != null && !data.pendingCardUses.isEmpty();
    }

    public static boolean contains(UUID player, UUID activation) {
        if (player == null || activation == null || !WorldLotteryPaths.ready()) return false;
        var uses = PlayerLotteryStore.get().getOrLoad(player).pendingCardUses;
        return uses != null && uses.containsKey(activation.toString());
    }

    static UUID activate(UUID player, String kind, String roleId) {
        if (player == null || kind == null || !WorldLotteryPaths.ready()) return null;
        var store = PlayerLotteryStore.get();
        if (store.isFlushDeferred() || store.isLoadFailed(player) || hasPending(player)) return null;
        boolean self = CardUseService.SELF_SELECT_KEY.equals(kind);
        FactionCardType faction = self ? FactionCardType.NONE : FactionCardType.fromString(kind);
        if (!self && faction == FactionCardType.NONE) return null;
        var cards = LocalBackpackStore.loadResult(player);
        if (cards.corrupt() || (self ? cards.selfSelectCards() : cards.cards().getOrDefault(kind, 0)) < 1) return null;
        var current = store.getOrLoad(player);
        if (store.isLoadFailed(player)) return null;
        long day = LotteryDayClock.monotonicEpochDay(self
                ? current.lastSelfSelectUseEpochDay : current.lastFactionCardUseEpochDay);
        UUID activation = UUID.randomUUID();
        String id = activation.toString();
        try {
            if (!store.applyToPlayerWithRollback(player, data -> {
                if (data.pendingCardUses == null) data.pendingCardUses = new HashMap<>();
                if (!data.pendingCardUses.isEmpty()) throw new IllegalStateException("Card reservation pending");
                if (self) {
                    if (data.lastSelfSelectUseEpochDay != day) {
                        data.lastSelfSelectUseEpochDay = day;
                        data.selfSelectUsesToday = 0;
                    }
                    if (data.selfSelectUsesToday >= DailySelfSelectService.MAX_DAILY_USES)
                        throw new IllegalStateException("Self-select quota exhausted");
                    data.selfSelectUsesToday++;
                } else {
                    if (data.lastFactionCardUseEpochDay != day) {
                        data.lastFactionCardUseEpochDay = day;
                        data.factionCardUsesToday = data.factionCardBonusUsesToday = 0;
                    }
                    long limit = (long) DailyFactionCardService.MAX_DAILY_USES + data.factionCardBonusUsesToday;
                    if (data.factionCardUsesToday >= limit) throw new IllegalStateException("Faction quota exhausted");
                    data.factionCardUsesToday++;
                }
                data.pendingCardUses.put(id, new PlayerLotteryData.CardUse(kind, roleId, day));
            })) return null;
            if (LocalBackpackStore.applyOnce(player, "card-debit:" + id, data -> {
                if (self) {
                    if (data.selfSelectCards < 1) throw new IllegalStateException("Missing self-select card");
                    data.selfSelectCards--;
                } else {
                    int count = data.cards.getOrDefault(kind, 0);
                    if (count < 1) throw new IllegalStateException("Missing faction card");
                    data.cards.put(kind, count - 1);
                }
            })) return activation;
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.warn("Card activation {} for {} interrupted: {}", id, player, failure.toString());
        }
        refund(player, activation);
        return null;
    }

    public static boolean refund(UUID player, UUID activation) {
        if (!contains(player, activation)) return false;
        var store = PlayerLotteryStore.get();
        String id = activation.toString();
        var use = store.getOrLoad(player).pendingCardUses.get(id);
        var cards = LocalBackpackStore.loadResult(player);
        if (use == null || cards.corrupt() || store.isLoadFailed(player)) return false;
        boolean self = CardUseService.SELF_SELECT_KEY.equals(use.kind);
        if (!self && FactionCardType.fromString(use.kind) == FactionCardType.NONE) return false;
        try {
            if (cards.rewardReceipts().contains("card-debit:" + id)
                    && !LocalBackpackStore.applyOnce(player, "card-refund:" + id, data -> {
                        if (self) data.selfSelectCards = Math.addExact(data.selfSelectCards, 1);
                        else data.cards.put(use.kind, Math.addExact(data.cards.getOrDefault(use.kind, 0), 1));
                    })) return false;
            return store.applyToPlayerWithRollback(player, data -> {
                if (data.pendingCardUses == null || data.pendingCardUses.remove(id) == null) return;
                if (self && data.lastSelfSelectUseEpochDay == use.day)
                    data.selfSelectUsesToday = Math.max(0, data.selfSelectUsesToday - 1);
                if (!self && data.lastFactionCardUseEpochDay == use.day)
                    data.factionCardUsesToday = Math.max(0, data.factionCardUsesToday - 1);
            });
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.error("Card activation refund {} remains pending for {}", id, player, failure);
            return false;
        }
    }

    /** A failed settlement write deliberately leaves an automatic restart refund (Mike's policy). */
    public static void settle(UUID player, UUID activation) {
        if (!contains(player, activation)) return;
        if (!PlayerLotteryStore.get().applyToPlayerWithRollback(player,
                data -> data.pendingCardUses.remove(activation.toString()))) {
            HabiLotteryMod.LOGGER.error("Card {} for {} used but settlement is not durable; restart will refund", activation, player);
        }
    }

    public static void recover(UUID player) {
        if (player == null || !WorldLotteryPaths.ready()) return;
        var store = PlayerLotteryStore.get();
        var data = store.getOrLoad(player);
        if (store.isLoadFailed(player) || data.pendingCardUses == null) return;
        for (String id : new ArrayList<>(data.pendingCardUses.keySet())) {
            try {
                UUID activation = UUID.fromString(id);
                // A reconnect during this live lobby/match is not a server restart.
                if (activation.equals(com.habitrain.lottery.backpack.ActiveCardForces.refundId(player))
                        || activation.equals(SelfSelectForces.refundId(player))) continue;
                refund(player, activation);
            }
            catch (IllegalArgumentException invalid) {
                HabiLotteryMod.LOGGER.error("Invalid card activation {} retained for {}", id, player);
            }
        }
    }

    public static void recoverAll() {
        if (!WorldLotteryPaths.ready()) return;
        try (var files = Files.list(WorldLotteryPaths.playersDir())) {
            for (var path : files.toList()) {
                String name = path.getFileName().toString();
                if (name.length() != 41 || !name.endsWith(".json")) continue;
                try { recover(UUID.fromString(name.substring(0, 36))); }
                catch (IllegalArgumentException invalid) { /* unrelated file */ }
            }
        } catch (java.io.IOException failure) {
            HabiLotteryMod.LOGGER.error("Cannot scan card activation journal", failure);
        }
    }
}
