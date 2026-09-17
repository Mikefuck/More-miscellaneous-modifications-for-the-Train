package com.habitrain.lottery.card;

import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
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
 * cleared at game end / true-start.
 */
public final class SelfSelectForces {
    private static final Map<UUID, ResourceLocation> FORCES = new ConcurrentHashMap<>();
    private static final Set<UUID> REFUND_PENDING = ConcurrentHashMap.newKeySet();

    private SelfSelectForces() {
    }

    /** Record a successfully self-selected exact role (uuid → canonical role id). */
    public static void record(UUID player, ResourceLocation roleId) {
        if (player == null || roleId == null) {
            return;
        }
        FORCES.put(player, canonicalize(roleId));
    }

    public static ResourceLocation get(UUID player) {
        ResourceLocation stored = FORCES.get(player);
        return stored == null ? null : canonicalize(stored);
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
        return FORCES.containsValue(canonical);
    }

    /** All role ids reserved by self-selection (deduplicated, already canonical). */
    public static Set<ResourceLocation> reservedRoles() {
        return new java.util.HashSet<>(FORCES.values());
    }

    public static void remove(UUID player) {
        FORCES.remove(player);
        REFUND_PENDING.remove(player);
    }

    public static void markRefundPending(UUID player) {
        if (has(player)) REFUND_PENDING.add(player);
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
    public static Map<UUID, ResourceLocation> snapshot() {
        return new HashMap<>(FORCES);
    }

    static ResourceLocation canonicalize(ResourceLocation roleId) {
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
