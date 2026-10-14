package com.habitrain.lottery.backpack;

import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;

import java.util.HashMap;
import java.util.Map;
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
    private record Activation(int roleType, UUID refundId) { }

    private static final Map<UUID, Activation> FORCES = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> REFUND_PENDING = ConcurrentHashMap.newKeySet();

    private ActiveCardForces() {
    }

    /** Record a successfully activated faction card (uuid → corrected role-type id). */
    public static void record(UUID player, FactionCardType type) {
        if (player == null || type == null || type == FactionCardType.NONE) {
            return;
        }
        FORCES.put(player, new Activation(FactionCardRoleTypes.roleTypeId(type), UUID.randomUUID()));
        REFUND_PENDING.remove(player);
    }

    public static Integer get(UUID player) {
        Activation activation = FORCES.get(player);
        return activation == null ? null : activation.roleType();
    }

    /** Stable across retries of this use, distinct even when the next use has the same card type. */
    public static UUID refundId(UUID player) {
        Activation activation = FORCES.get(player);
        return activation == null ? null : activation.refundId();
    }

    public static void remove(UUID player) {
        FORCES.remove(player);
        REFUND_PENDING.remove(player);
    }

    public static void markRefundPending(UUID player) {
        if (FORCES.containsKey(player)) REFUND_PENDING.add(player);
    }

    public static boolean refundPending(UUID player) {
        return REFUND_PENDING.contains(player);
    }

    public static void clear() {
        FORCES.clear();
        REFUND_PENDING.clear();
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
}
