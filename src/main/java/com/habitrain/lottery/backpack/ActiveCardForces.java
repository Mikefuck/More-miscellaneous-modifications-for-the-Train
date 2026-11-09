package com.habitrain.lottery.backpack;

import com.habitrain.lottery.card.ActiveForcesStore;
import com.habitrain.lottery.grant.LoginRewardService;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks faction cards activated since the last role assignment.
 *
 * <p>{@code PlayerRoleWeightManager.ForcePlayerTeam} also receives streak-based 保底 forces
 * (high-streak players auto-forced to their highest-scored type), which must NOT be treated as
 * card guarantees. This registry keeps the "real card" subset (uuid → corrected role-type id)
 * so {@link com.habitrain.lottery.grant.CardForceGuaranteeHook} can guarantee exactly those.
 */
public final class ActiveCardForces {
    public record Reservation(int roleType, UUID refundId, long epochDay) { }

    private static final Map<UUID, Reservation> FORCES = new ConcurrentHashMap<>();
    private static final Set<UUID> REFUND_PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> CANDIDATE_OFFERED = ConcurrentHashMap.newKeySet();

    private ActiveCardForces() {
    }

    /** Record a successfully activated faction card (uuid → corrected role-type id). */
    public static void record(UUID player, FactionCardType type) {
        if (player == null || type == null || type == FactionCardType.NONE) {
            return;
        }
        int roleType = FactionCardRoleTypes.roleTypeId(type);
        if (roleType < 0) {
            return;
        }
        long recordedDay = -1L;
        if (com.habitrain.lottery.storage.WorldLotteryPaths.ready()) {
            var store = com.habitrain.lottery.storage.PlayerLotteryStore.get();
            if (!store.isLoadFailed(player)) {
                recordedDay = store.getOrLoad(player).lastFactionCardUseEpochDay;
            }
        }
        long effectiveDay = com.habitrain.lottery.time.LotteryDayClock.monotonicEpochDay(recordedDay);
        FORCES.put(player, new Reservation(roleType, UUID.randomUUID(), effectiveDay));
        REFUND_PENDING.remove(player);
        CANDIDATE_OFFERED.remove(player);
        ActiveForcesStore.sync();
    }

    /** Restore a persisted reservation during crash recovery without regenerating its refundId. */
    public static void restore(UUID player, int roleType, UUID refundId, long epochDay) {
        if (player == null || refundId == null || roleType < 0) {
            return;
        }
        FORCES.put(player, new Reservation(roleType, refundId, epochDay));
        REFUND_PENDING.remove(player);
        CANDIDATE_OFFERED.remove(player);
    }

    public static Integer get(UUID player) {
        if (player == null) {
            return null;
        }
        Reservation activation = FORCES.get(player);
        return activation == null ? null : activation.roleType();
    }

    /** Stable across retries of this use, distinct even when the next use has the same card type. */
    public static UUID refundId(UUID player) {
        if (player == null) {
            return null;
        }
        Reservation activation = FORCES.get(player);
        return activation == null ? null : activation.refundId();
    }

    public static long activationEpochDay(UUID player) {
        if (player == null) {
            return LoginRewardService.todayEpochDayUtc();
        }
        Reservation activation = FORCES.get(player);
        return activation == null ? LoginRewardService.todayEpochDayUtc() : activation.epochDay();
    }

    /**
     * Marks that a rotation draft round actually offered {@code >= 1} candidate role matching
     * the player's forced faction ({@code B-12}). Voluntary selection of a different role in
     * that draft will consume the card at {@code OnGameTrueStarted} instead of refunding it,
     * while an aborted match before {@code OnGameTrueStarted} still refunds via {@code OnGameEnd}.
     */
    public static void markCandidateOffered(UUID player) {
        if (player != null && FORCES.containsKey(player)) {
            CANDIDATE_OFFERED.add(player);
        }
    }

    public static boolean isCandidateOffered(UUID player) {
        return player != null && CANDIDATE_OFFERED.contains(player);
    }

    /**
     * Clears {@code CANDIDATE_OFFERED} once the player has genuinely picked a draft role
     * matching their forced faction before the post-draft balancer runs ({@code B-12}).
     * If the balancer later overwrites their Civilian pick with an underfilled role,
     * settlement will refund the card instead of treating it as a voluntary skip.
     */
    public static void clearCandidateOffered(UUID player) {
        if (player != null) {
            CANDIDATE_OFFERED.remove(player);
        }
    }

    public static void remove(UUID player) {
        if (player == null) {
            return;
        }
        Reservation reservation = FORCES.remove(player);
        boolean removed = reservation != null;
        if (removed) com.habitrain.lottery.card.CardActivationJournal.settle(player, reservation.refundId());
        REFUND_PENDING.remove(player);
        CANDIDATE_OFFERED.remove(player);
        if (removed) {
            ActiveForcesStore.sync();
        }
    }

    public static void markRefundPending(UUID player) {
        if (player != null && FORCES.containsKey(player)) REFUND_PENDING.add(player);
    }

    public static boolean refundPending(UUID player) {
        return player != null && REFUND_PENDING.contains(player);
    }

    /** Clears in-memory state only (does not wipe crash-recovery disk state). */
    public static void clear() {
        FORCES.clear();
        REFUND_PENDING.clear();
        CANDIDATE_OFFERED.clear();
    }

    public static boolean isEmpty() {
        return FORCES.isEmpty();
    }

    /** Snapshot for safe iteration while entries may be removed. */
    public static Map<UUID, Integer> snapshot() {
        Map<UUID, Integer> snapshot = new HashMap<>();
        FORCES.forEach((player, activation) -> snapshot.put(player, activation.roleType()));
        return snapshot;
    }

    public static Map<UUID, Reservation> reservationsSnapshot() {
        return new HashMap<>(FORCES);
    }
}
