package com.habitrain.lottery.card;

import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.lottery.grant.LoginRewardService;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player-activated "自选角色" (exact-role self-select) forces since the
 * last role assignment.
 *
 * <p>Unlike {@link com.habitrain.lottery.backpack.ActiveCardForces} (which only
 * remembers the faction), this registry remembers the exact {@link
 * io.wifi.starrailexpress.api.SRERole} the player chose, so the assignment hook
 * can put that precise role into {@code roleAssignments} and reserve it against
 * duplication by other card users. Lifecycle matches {@code ActiveCardForces}:
 * confirmed at {@code OnGameTrueStarted} or refunded on match abort / crash recovery.
 */
public final class SelfSelectForces {
    public record Reservation(ResourceLocation roleId, UUID refundId, long epochDay) { }

    private static final Map<UUID, Reservation> FORCES = new ConcurrentHashMap<>();
    private static final Set<UUID> REFUND_PENDING = ConcurrentHashMap.newKeySet();

    private SelfSelectForces() {
    }

    /** Record a successfully self-selected exact role (uuid → canonical role id). */
    public static void record(UUID player, ResourceLocation roleId) {
        if (player == null || roleId == null) {
            return;
        }
        long recordedDay = -1L;
        if (com.habitrain.lottery.storage.WorldLotteryPaths.ready()) {
            var store = com.habitrain.lottery.storage.PlayerLotteryStore.get();
            if (!store.isLoadFailed(player)) {
                recordedDay = store.getOrLoad(player).lastSelfSelectUseEpochDay;
            }
        }
        long effectiveDay = com.habitrain.lottery.time.LotteryDayClock.monotonicEpochDay(recordedDay);
        FORCES.put(player, new Reservation(
                canonicalize(roleId),
                UUID.randomUUID(),
                effectiveDay));
        REFUND_PENDING.remove(player);
        ActiveForcesStore.sync();
    }

    /** Restore a persisted reservation during crash recovery without regenerating its refundId. */
    public static void restore(UUID player, ResourceLocation roleId, UUID refundId, long epochDay) {
        if (player == null || roleId == null || refundId == null) {
            return;
        }
        FORCES.put(player, new Reservation(canonicalize(roleId), refundId, epochDay));
        REFUND_PENDING.remove(player);
    }

    public static ResourceLocation get(UUID player) {
        if (player == null) {
            return null;
        }
        Reservation stored = FORCES.get(player);
        return stored == null ? null : canonicalize(stored.roleId());
    }

    public static UUID refundId(UUID player) {
        if (player == null) {
            return null;
        }
        Reservation stored = FORCES.get(player);
        return stored == null ? null : stored.refundId();
    }

    public static long activationEpochDay(UUID player) {
        if (player == null) {
            return LoginRewardService.todayEpochDayUtc();
        }
        Reservation stored = FORCES.get(player);
        return stored == null ? LoginRewardService.todayEpochDayUtc() : stored.epochDay();
    }

    public static boolean has(UUID player) {
        return player != null && FORCES.containsKey(player);
    }

    /** Whether any player in this lobby has self-selected the given role. */
    public static boolean isRoleTaken(ResourceLocation roleId) {
        if (roleId == null) {
            return false;
        }
        ResourceLocation canonical = canonicalize(roleId);
        for (Reservation res : FORCES.values()) {
            if (res != null && canonical.equals(canonicalize(res.roleId()))) {
                return true;
            }
        }
        return false;
    }

    /** All role ids reserved by self-selection (deduplicated, already canonical). */
    public static Set<ResourceLocation> reservedRoles() {
        Set<ResourceLocation> out = new HashSet<>();
        for (Reservation res : FORCES.values()) {
            if (res != null && res.roleId() != null) {
                out.add(canonicalize(res.roleId()));
            }
        }
        return out;
    }

    public static void remove(UUID player) {
        if (player == null) {
            return;
        }
        Reservation reservation = FORCES.remove(player);
        boolean removed = reservation != null;
        if (removed) CardActivationJournal.settle(player, reservation.refundId());
        REFUND_PENDING.remove(player);
        if (removed) {
            ActiveForcesStore.sync();
        }
    }

    public static void markRefundPending(UUID player) {
        if (has(player)) REFUND_PENDING.add(player);
    }

    public static boolean refundPending(UUID player) {
        return player != null && REFUND_PENDING.contains(player);
    }

    /** Clears in-memory state only (does not wipe crash-recovery disk state). */
    public static void clear() {
        FORCES.clear();
        REFUND_PENDING.clear();
    }

    public static boolean isEmpty() {
        return FORCES.isEmpty();
    }

    /** Snapshot for safe iteration while entries may be removed. */
    public static Map<UUID, ResourceLocation> snapshot() {
        Map<UUID, ResourceLocation> out = new HashMap<>();
        FORCES.forEach((uid, res) -> {
            if (res != null && res.roleId() != null) {
                out.put(uid, canonicalize(res.roleId()));
            }
        });
        return out;
    }

    public static Map<UUID, Reservation> reservationsSnapshot() {
        return new HashMap<>(FORCES);
    }

    public static ResourceLocation canonicalize(ResourceLocation roleId) {
        if (roleId == null) {
            return null;
        }
        try {
            RoleKey key = RoleCatalogApi.instance().canonicalize(roleId);
            return key == null ? roleId : key.location();
        } catch (Throwable t) {
            return roleId;
        }
    }
}
