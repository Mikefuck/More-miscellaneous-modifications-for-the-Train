package com.habitrain.lottery.grant;

import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchSettlement;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.role.v2.EffectiveRoleProfile;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.api.CustomWinnerRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Participate and faction win grants. Subscribes to core {@link MatchEvents#ROUND_ENDED}
 * instead of SRE {@code OnGameEnd} / round-end CCA.
 *
 * <p>Win grants:
 * <ul>
 *   <li>乘客 / innocent → 1 抽 ({@code win_passenger})</li>
 *   <li>杀手 / killer team → 2 抽 ({@code win_killer})</li>
 *   <li>中立 / custom / independent → 5 抽 ({@code win_neutral})</li>
 * </ul>
 */
public final class GrantEventHooks {
    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * Round fingerprint → matchKey. Same string as {@link MatchSettlement#matchKey()}.
     */
    private static final Map<String, String> GRANTED_BY_FINGERPRINT =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 32;
                }
            });

    public enum WinFaction {
        PASSENGER("win_passenger"),
        KILLER("win_killer"),
        NEUTRAL("win_neutral");

        public final String eventId;

        WinFaction(String eventId) {
            this.eventId = eventId;
        }

        static WinFaction from(MatchWinFaction faction) {
            if (faction == null) {
                return null;
            }
            return switch (faction) {
                case KILLER -> KILLER;
                case NEUTRAL -> NEUTRAL;
                case PASSENGER -> PASSENGER;
            };
        }
    }

    private GrantEventHooks() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            HabiLotteryMod.LOGGER.debug("GrantEventHooks.register skipped (already registered)");
            return;
        }
        try {
            MatchEvents.ROUND_ENDED.register(GrantEventHooks::onRoundEnded);
            HabiLotteryMod.LOGGER.info("Registered MatchEvents.ROUND_ENDED grant hooks");
        } catch (Throwable t) {
            REGISTERED.set(false);
            HabiLotteryMod.LOGGER.warn("MatchEvents.ROUND_ENDED hook unavailable: {}", t.toString());
        }
    }

    private static void onRoundEnded(ServerLevel level, MatchSettlement settlement) {
        if (level == null || settlement == null) {
            return;
        }
        if (!PlayerLotteryStore.get().isTakeoverActive()) {
            HabiLotteryMod.LOGGER.warn("Skip grants: lottery takeover inactive match={}", settlement.matchKey());
            return;
        }
        String fingerprint = settlement.matchKey();
        String matchKey = fingerprint;
        String existing;
        synchronized (GRANTED_BY_FINGERPRINT) {
            existing = GRANTED_BY_FINGERPRINT.putIfAbsent(fingerprint, matchKey);
        }
        if (existing != null) {
            HabiLotteryMod.LOGGER.info(
                    "Skip duplicate ROUND_ENDED fingerprint={} matchKey={}", fingerprint, existing);
            return;
        }
        String mode = settlement.modeId();
        String classified = classifyModeId(mode);
        Set<UUID> matchPlayers = participateSet(level, settlement);
        if ("unknown".equals(classified)) {
            HabiLotteryMod.LOGGER.warn(
                    "Skip participate grant: mode unknown (raw={}) fingerprint={}", mode, fingerprint);
        } else {
            String participateEvent = GameModeIds.isBlackout(classified) || GameModeIds.isBlackout(mode)
                    ? "blackout_participate"
                    : "sre_participate";
            for (UUID id : matchPlayers) {
                LotteryGrantService.grantEvent(id, participateEvent, classified, matchKey);
            }
        }
        grantFactionWins(level, settlement);
    }

    /**
     * Classify a settlement / CCA mode id into short lottery mode names.
     * Unknown must not default to murder (wrong participate event).
     */
    static String classifyModeId(String modeId) {
        String fromNames = detectModeFromNames(modeId, modeId);
        if (fromNames != null) {
            return fromNames;
        }
        if (modeId != null) {
            if (GameModeIds.isBlackout(modeId)) {
                return "blackout";
            }
            if (GameModeIds.isRepair(modeId)) {
                return "repair";
            }
            if (GameModeIds.isMurder(modeId)) {
                return "murder";
            }
        }
        return fallbackMode(false);
    }

    /** @return mode id, or {@code null} when names do not identify a known mode */
    static String detectModeFromNames(String classSimpleName, String idString) {
        if (classSimpleName != null) {
            String name = classSimpleName.toLowerCase();
            if (name.contains("blackout")) {
                return "blackout";
            }
            if (name.contains("repair")) {
                return "repair";
            }
            if (name.contains("murder") || name.contains("kill")) {
                return "murder";
            }
        }
        if (idString != null && idString != classSimpleName) {
            String lower = idString.toLowerCase();
            if (lower.contains("blackout")) {
                return "blackout";
            }
            if (lower.contains("repair")) {
                return "repair";
            }
            if (lower.contains("murder") || lower.contains("kill")) {
                return "murder";
            }
        }
        return null;
    }

    static String fallbackMode(boolean blackoutRoundKnown) {
        return blackoutRoundKnown ? "blackout" : "unknown";
    }

    /** Durable match key: dimension + start world tick + start millis (not a process SEQ). */
    static String newMatchKey(String dimensionLocation, long startWorldTick, long startMillis) {
        return formatRoundFingerprint(dimensionLocation, startWorldTick, startMillis);
    }

    static String formatRoundFingerprint(String dimensionLocation, long startWorldTick, long startMillis) {
        String dim = dimensionLocation == null || dimensionLocation.isBlank() ? "unknown" : dimensionLocation;
        return dim + "|" + startWorldTick + "|" + startMillis;
    }

    private static void grantFactionWins(ServerLevel level, MatchSettlement settlement) {
        Set<UUID> winners = settlement.winners();
        String matchKey = settlement.matchKey();
        String mode = settlement.modeId();
        if (winners.isEmpty()) {
            HabiLotteryMod.LOGGER.info(
                    "No winners for match {} mode={} winKind={}", matchKey, mode, settlement.winKind());
            return;
        }
        int granted = 0;
        for (UUID id : winners) {
            if (isUnassignedForWinGrant(id, level, mode)) {
                HabiLotteryMod.LOGGER.info(
                        "Skip win grant for {} (no role / no blackout faction)", id);
                continue;
            }
            WinFaction faction = classifyWinFaction(id, level, settlement);
            if (faction == null) {
                HabiLotteryMod.LOGGER.info(
                        "Skip win grant for {} (unclassified faction)", id);
                continue;
            }
            LotteryGrantService.grantEvent(id, faction.eventId, mode, matchKey + ":win");
            granted++;
            ServerPlayer online = findOnline(level, id);
            HabiLotteryMod.LOGGER.info(
                    "Win grant {} -> {} ({}) match={} mode={} winKind={}",
                    faction.eventId,
                    online != null ? online.getGameProfile().getName() : id,
                    faction.name().toLowerCase(),
                    matchKey,
                    mode,
                    settlement.winKind());
        }
        HabiLotteryMod.LOGGER.info(
                "Faction win grants done: match={} mode={} winKind={} winners={} granted={}",
                matchKey, mode, settlement.winKind(), winners.size(), granted);
    }

    /**
     * Prefer 入局 UUID (blackout history ∪ assigned roles). {@code settlement.participants()}
     * may still contain roundEnd spectators and is only the fallback when both sets are empty.
     */
    static Set<UUID> participateSet(ServerLevel level, MatchSettlement settlement) {
        Set<UUID> roundEnd = settlement == null ? Set.of() : settlement.participants();
        Set<UUID> history = new LinkedHashSet<>();
        Set<UUID> assigned = new LinkedHashSet<>();
        collectHistoryAndAssigned(level, settlement == null ? null : settlement.modeId(), history, assigned);
        return GrantRecipients.matchParticipants(roundEnd, history, assigned);
    }

    static boolean isUnassignedForWinGrant(UUID id, ServerLevel level, String modeId) {
        return WinFactionRules.isUnassignedForWinGrant(
                hasAssignedRole(id, level),
                hasBlackoutFactionHistory(id, level, modeId));
    }

    private static void collectHistoryAndAssigned(
            ServerLevel level, String modeId, Set<UUID> history, Set<UUID> assigned) {
        if (GameModeIds.isBlackout(modeId) && level != null) {
            try {
                Map<UUID, ?> roles = BlackoutRoleManager.getRoleHistory(level);
                if (roles != null) {
                    history.addAll(roles.keySet());
                }
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.debug("blackout role history unavailable: {}", t.toString());
            }
        }
        if (level == null) {
            return;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null && game.getRoles() != null) {
                assigned.addAll(game.getRoles().keySet());
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("game role map unavailable: {}", t.toString());
        }
    }

    /**
     * Blackout uses {@link BlackoutRoleManager#getFactionForEnd}; murder uses the
     * ended-round frozen {@link EffectiveRoleProfile}, not live {@code SRERole} flags.
     */
    static WinFaction classifyWinFaction(UUID id, ServerLevel level, MatchSettlement settlement) {
        if (id == null) {
            return null;
        }
        String mode = settlement == null ? null : settlement.modeId();
        if (GameModeIds.isBlackout(mode) && level != null) {
            WinFaction blackout = classifyBlackout(id, level, settlement);
            if (blackout != null) {
                return blackout;
            }
        }
        SRERole raw = assignedRole(id, level);
        return classifyFromCatalog(raw, GrantRoleSnapshot.endedProfile(raw).orElse(null));
    }

    static WinFaction classifyFromCatalog(SRERole raw, EffectiveRoleProfile profile) {
        boolean customWinner = raw instanceof CustomWinnerRole;
        if (profile != null) {
            return WinFactionRules.fromProfile(profile, customWinner);
        }
        if (raw == null) {
            return null;
        }
        return WinFactionRules.fromRoleFlags(
                raw.isNeutrals(),
                customWinner,
                raw.isNeutralForInnocent(),
                raw.isNeutralForKiller(),
                raw.isKiller(),
                raw.isKillerTeam(),
                raw.canUseKiller(),
                raw.isMafiaTeam());
    }

    private static WinFaction classifyBlackout(UUID id, ServerLevel level, MatchSettlement settlement) {
        try {
            BlackoutRoleManager.Faction faction = BlackoutRoleManager.getFactionForEnd(level, id);
            boolean killerWon = settlement != null
                    && settlement.winKind() != null
                    && settlement.winKind().isKillerWin();
            return WinFactionRules.fromBlackoutName(
                    faction == null ? null : faction.name(), killerWon);
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("blackout faction lookup failed: {}", t.toString());
            return null;
        }
    }

    private static boolean hasAssignedRole(UUID id, ServerLevel level) {
        return assignedRole(id, level) != null;
    }

    private static SRERole assignedRole(UUID id, ServerLevel level) {
        if (id == null || level == null) {
            return null;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null) {
                return null;
            }
            return game.getRole(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasBlackoutFactionHistory(UUID id, ServerLevel level, String modeId) {
        if (id == null || level == null || !GameModeIds.isBlackout(modeId)) {
            return false;
        }
        try {
            if (BlackoutRoleManager.getFaction(level, id) != null) {
                return true;
            }
            return BlackoutRoleManager.getRoleHistory(level).containsKey(id);
        } catch (Throwable t) {
            return false;
        }
    }

    private static ServerPlayer findOnline(ServerLevel level, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            MinecraftServer server = level != null ? level.getServer() : HabiLotteryMod.getServer();
            if (server == null) {
                return null;
            }
            return server.getPlayerList().getPlayer(uuid);
        } catch (Throwable t) {
            return null;
        }
    }
}
