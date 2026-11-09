package com.habitrain.lottery.grant;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.FactionCardRoleTypes;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.daily.config.DailyTaskTracker;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.mail.RewardDelivery;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Settles faction cards against final assignments without changing any role. */
public final class CardForceGuaranteeHook {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final LobbyRewardRecovery RECOVERY = new LobbyRewardRecovery();
    private static MinecraftServer recoveryServer;

    private CardForceGuaranteeHook() {}

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        OnGameTrueStarted.EVENT.register(CardForceGuaranteeHook::settleFinalRoles);
        // Also return cards when preparation is cancelled before final roles exist, and
        // resume any card reward intents that were deferred while the match was active (B-03).
        OnGameEnd.EVENT.register((level, game) -> {
            refundPendingCards();
            if (level != null && level.getServer() != null) {
                recoveryServer = level.getServer();
                RECOVERY.schedule(recoveryServer.getTickCount(), recoveryServer.getPlayerList().getPlayers()
                        .stream().map(ServerPlayer::getUUID).toList());
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server != recoveryServer) return;
            RECOVERY.recover(server.getTickCount(), uuid -> {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                return player == null || player.hasDisconnected()
                        || !ServerMatchGate.hasActiveMatch(player) && !PlayerStateGate.spectatorRestOrDead(player);
            }, uuid -> {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null && !player.hasDisconnected()) RewardDelivery.recoverPending(player);
            });
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (server == recoveryServer) {
                RECOVERY.clear();
                recoveryServer = null;
            }
        });
        HabiLotteryMod.LOGGER.info("Registered final faction-card settlement");
    }

    private static void settleFinalRoles(ServerLevel level) {
        var roles = SREGameWorldComponent.KEY.get(level).getRoles();
        for (UUID uid : ActiveCardForces.snapshot().keySet()) {
            try {
                Player player = level.getPlayerByUUID(uid);
                boolean presentInMatch = player != null;
                SRERole role = presentInMatch ? roles.get(uid) : null;
                ServerPlayer online = player instanceof ServerPlayer sp ? sp : null;
                settleCard(uid, role == null ? null : role.getRoleType(), presentInMatch, online);
            } catch (RuntimeException e) {
                HabiLotteryMod.LOGGER.error("Faction-card settlement failed for {}", uid, e);
            }
        }
        PlayerRoleWeightManager.ForcePlayerTeam.clear();
    }

    static void settleCard(UUID uid, Integer assignedType) {
        settleCard(uid, assignedType, assignedType != null || ActiveCardForces.isCandidateOffered(uid), null);
    }

    static void settleCard(UUID uid, Integer assignedType, boolean presentInMatch, ServerPlayer online) {
        Integer requested = ActiveCardForces.get(uid);
        if (requested == null) return;
        boolean honored = !ActiveCardForces.refundPending(uid)
                && presentInMatch
                && (requested.equals(assignedType) || ActiveCardForces.isCandidateOffered(uid));
        if (honored) {
            FactionCardType cardType = FactionCardRoleTypes.fromRoleTypeId(requested);
            ActiveCardForces.remove(uid);
            if (online != null && cardType != FactionCardType.NONE) {
                DailyTaskTracker.onCardUsed(online, cardType.name().toLowerCase(Locale.ROOT));
            }
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
