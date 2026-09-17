package com.habitrain.lottery.grant;

import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.lottery.HabiLotteryMod;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ensures game balance conservation when cards or self-selections overwrite
 * already-assigned roles: if a player holding a Killer or Vigilante (or special)
 * role is overwritten with another role (e.g. Neutral), the displaced role is
 * safely transferred to a free, unprotected civilian player.
 */
public final class RoleDisplacementHelper {

    private RoleDisplacementHelper() {
    }

    /**
     * Whether the given role is an ordinary civilian role.
     */
    public static boolean isCivilian(SRERole r) {
        if (r == null || r.identifier() == null) {
            return true;
        }
        try {
            RoleCatalogApi api = RoleCatalogApi.instance();
            RoleKey civilianKey = api.canonicalize(RoleKey.of("starrailexpress", "civilian").location());
            if (civilianKey != null && civilianKey.equals(api.canonicalize(r.identifier()))) {
                return true;
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.error("catalog civilian check failed for {}", r.identifier(), t);
        }
        return r.isInnocent() && !r.isVigilanteTeam() && !r.canUseKiller();
    }

    /**
     * Whether the role is a special role (Killer, Vigilante, or critical special)
     * that must not be deleted when overwritten.
     */
    public static boolean isSpecialRole(SRERole r) {
        if (r == null || isCivilian(r)) {
            return false;
        }
        return r.canUseKiller() || r.isVigilanteTeam() || !r.isInnocent();
    }

    /**
     * Safely transfers a displaced role (e.g. a Killer role from a player who was
     * guaranteed a Neutral role) to an unprotected civilian player.
     *
     * @param serverWorld    world context
     * @param roleAssignments mutable assignments map
     * @param displacedRole  the role that was overwritten and needs a new holder
     * @param protectedUids  UUIDs of players who used cards/self-selects and must not be overwritten
     * @return true if transferred, false if no suitable civilian was available
     */
    public static boolean transferDisplacedRole(
            ServerLevel serverWorld,
            Map<Player, SRERole> roleAssignments,
            SRERole displacedRole,
            Set<UUID> protectedUids) {
        if (displacedRole == null || isCivilian(displacedRole) || roleAssignments == null) {
            return false;
        }

        // 1. Look for a live player who is unassigned or currently a plain civilian
        for (Map.Entry<Player, SRERole> entry : roleAssignments.entrySet()) {
            Player p = entry.getKey();
            if (!(p instanceof ServerPlayer sp) || !sp.isAlive()) {
                continue;
            }
            if (protectedUids != null && protectedUids.contains(p.getUUID())) {
                continue;
            }
            SRERole currentRole = entry.getValue();
            if (currentRole == null || isCivilian(currentRole)) {
                roleAssignments.put(p, displacedRole);
                HabiLotteryMod.LOGGER.info(
                        "Displaced special role {} successfully reassigned to civilian player {}",
                        displacedRole.identifier(), p.getGameProfile().getName());
                return true;
            }
        }

        HabiLotteryMod.LOGGER.warn(
                "Could not find unprotected civilian to receive displaced role {}",
                displacedRole.identifier());
        return false;
    }
}
