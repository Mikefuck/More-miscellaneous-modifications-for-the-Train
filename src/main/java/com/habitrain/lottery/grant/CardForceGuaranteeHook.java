package com.habitrain.lottery.grant;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.card.CardUseService;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import net.minecraft.server.level.ServerLevel;
import org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Settles faction cards against final assignments without changing any role. */
public final class CardForceGuaranteeHook {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private CardForceGuaranteeHook() {}

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        OnGameTrueStarted.EVENT.register(CardForceGuaranteeHook::settleFinalRoles);
        // Also return cards when preparation is cancelled before final roles exist.
        OnGameEnd.EVENT.register((level, game) -> refundPendingCards());
        HabiLotteryMod.LOGGER.info("Registered final faction-card settlement");
    }

    private static void settleFinalRoles(ServerLevel level) {
        var roles = SREGameWorldComponent.KEY.get(level).getRoles();
        for (UUID uid : ActiveCardForces.snapshot().keySet()) {
            try {
                SRERole role = level.getPlayerByUUID(uid) == null ? null : roles.get(uid);
                settleCard(uid, role == null ? null : role.getRoleType());
            } catch (RuntimeException e) {
                HabiLotteryMod.LOGGER.error("Faction-card settlement failed for {}", uid, e);
            }
        }
        PlayerRoleWeightManager.ForcePlayerTeam.clear();
    }

    static void settleCard(UUID uid, Integer assignedType) {
        Integer requested = ActiveCardForces.get(uid);
        if (requested == null) return;
        if (!ActiveCardForces.refundPending(uid) && requested.equals(assignedType)) {
            ActiveCardForces.remove(uid);
        } else {
            CardUseService.refundForcedCard(uid);
        }
    }

    public static void refundPendingCards() {
        for (UUID uid : ActiveCardForces.snapshot().keySet()) {
            try {
                CardUseService.refundForcedCard(uid);
            } catch (RuntimeException e) {
                HabiLotteryMod.LOGGER.error("Pending faction-card refund failed for {}", uid, e);
            }
        }
    }
}
