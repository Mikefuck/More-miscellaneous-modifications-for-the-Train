package com.habitrain.lottery.card;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.DailyFactionCardService;
import com.habitrain.lottery.backpack.DailySelfSelectService;
import com.habitrain.lottery.backpack.PlayerCardAdminService;
import com.habitrain.lottery.backpack.FactionCardRoleTypes;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.backpack.LimitBreakCardService;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.QueryPurpose;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleForceApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleQuery;
import com.habitrain.lottery.network.CardUseMenuS2C;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.backpack.BackpackManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.exmo.sre.repair.role.RepairRole;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.Harpymodloader;
import org.agmas.harpymodloader.SREDisableManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-authoritative, separate faction and exact-role card activation. */
public final class CardUseService {
    public static final String MODE_DIRECT = "direct";
    public static final String MODE_SELF = "self";
    public static final String SELF_SELECT_KEY = "self_select";
    public static final String INVENTORY_KEY = "inventory";
    public static final String LIMIT_BREAK_KEY = "limit_break";
    public static final int SELF_SELECT_COST = 1;

    private static final Gson GSON = new Gson();
    private static final java.util.Set<UUID> REFUNDED_SELF_BALANCES = new java.util.HashSet<>();

    /** Serializable candidate role sent to the client for rendering. */
    public static final class RoleCandidate {
        public String id;
        public String name;   // translation key (announcement.star.role.<path>) or display name
        public int color;
        public String bound;  // bound role id path, or "" when no occupation binding
        /**
         * Already reserved by another player's self-select in this lobby. The client greys
         * these out so a player never picks a role the server would reject in
         * {@link #doSelfSelect}.
         */
        public boolean taken;

        RoleCandidate(SRERole role) {
            this.id = role.identifier().toString();
            Component nameComp = role.getName();
            if (nameComp != null && nameComp.getContents() instanceof TranslatableContents tc) {
                this.name = tc.getKey();
            } else if (nameComp != null) {
                this.name = nameComp.getString();
            } else {
                this.name = "announcement.star.role." + role.identifier().getPath();
            }
            this.color = role.getColor();
            List<SRERole> companions = role.getoccupationRoles();
            if (companions != null && !companions.isEmpty() && companions.get(0) != null) {
                this.bound = companions.get(0).identifier().toString();
            } else {
                this.bound = "";
            }
        }
    }

    private CardUseService() {
    }

    // =====================================================================
    // C2S request (client clicked a card row in the backpack GUI)
    // =====================================================================

    public static void handleRequest(ServerPlayer player, String questKey) {
        if (player == null) return;
        try {
            if (!PlayerLotteryStore.get().isTakeoverActive() || rejectIfNotLobby(player)) return;
            boolean self = SELF_SELECT_KEY.equals(questKey);
            boolean inventory = INVENTORY_KEY.equals(questKey);
            FactionCardType type = FactionCardType.fromString(questKey == null ? "" : questKey);
            if (!self && !inventory && (type == null || type == FactionCardType.NONE)) return;
            DailyFactionCardService.hasReachedLimit(player); // Normalize the faction quota day.
            List<RoleCandidate> candidates = new ArrayList<>();
            if (self) {
                // 标记已被本局其他玩家占用的职业：客户端据此置灰，避免玩家点到一个
                // 服务端必然拒绝的角色（只能靠聊天栏报错逐个试错）。
                java.util.Set<ResourceLocation> reserved = SelfSelectForces.reservedRoles();
                for (SRERole role : listCandidates(FactionCardType.NONE)) {
                    RoleCandidate candidate = new RoleCandidate(role);
                    candidate.taken = reserved.contains(
                            SelfSelectForces.canonicalize(role.identifier()));
                    candidates.add(candidate);
                }
            }
            int factionRemaining = DailyFactionCardService.remaining(player);
            java.util.Map<String, Integer> balances = new java.util.LinkedHashMap<>(
                    PlayerCardAdminService.snapshotOnline(player).cards());
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                    new CardUseMenuS2C(questKey, factionRemaining, GSON.toJson(candidates),
                            GSON.toJson(balances),
                            DailySelfSelectService.remaining(player.getUUID())));
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Card menu request failed for {}", player.getUUID(), t);
        }
    }

    public static void handleConfirm(ServerPlayer player, String questKey, String mode, String roleId) {
        if (player == null) return;
        try {
            if (!PlayerLotteryStore.get().isTakeoverActive() || rejectIfNotLobby(player)) return;
            if (LIMIT_BREAK_KEY.equals(questKey) && "bonus".equals(mode)) {
                if (!LimitBreakCardService.useFromBackpack(player)) {
                    player.sendSystemMessage(Component.translatable("message.habitrain_lottery.limit_break.use_failed"));
                } else {
                    com.habitrain.lottery.daily.config.DailyTaskTracker.onCardUsed(player, LIMIT_BREAK_KEY);
                }
            } else if (SELF_SELECT_KEY.equals(questKey) && MODE_SELF.equals(mode)) {
                doSelfSelect(player, roleId == null ? "" : roleId);
            } else if (MODE_DIRECT.equals(mode) && !SELF_SELECT_KEY.equals(questKey)) {
                FactionCardType type = FactionCardType.fromString(questKey == null ? "" : questKey);
                if (type != null && type != FactionCardType.NONE) doDirectUse(player, type);
            }
            handleRequest(player, INVENTORY_KEY);
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Card use confirm failed for {}", player.getUUID(), t);
            player.sendSystemMessage(Component.translatable(
                    "message.habitrain_lottery.card.use_error", String.valueOf(t.getMessage())));
        }
    }

    /**
     * Full lobby, storage, corruption, exclusivity, and daily-quota gate shared by GUI
     * activation and upstream {@code /sre:pass activate} ({@code BackpackManagerMixin} {@code B-13}).
     */
    public static boolean canActivateFactionCard(ServerPlayer player, FactionCardType type) {
        if (player == null || type == null || type == FactionCardType.NONE) {
            return false;
        }
        UUID uuid = player.getUUID();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive() || store.isLoadFailed(uuid) || store.isFlushDeferred()) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.card.storage_unavailable"));
            return false;
        }
        if (rejectIfNotLobby(player)) {
            return false;
        }
        if (LocalBackpackStore.loadResult(uuid).corrupt()) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.card.storage_corrupt"));
            return false;
        }
        if (SelfSelectForces.has(uuid) || ActiveCardForces.get(uuid) != null || isRoleForceQueued(uuid)
                || CardActivationJournal.hasPending(uuid)) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.card.already_active"));
            return false;
        }
        if (DailyFactionCardService.hasReachedLimit(player)) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.card.daily_limit_reached"));
            return false;
        }
        return true;
    }

    public static boolean isRoleForceQueued(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            return RoleForceApi.instance().isQueued(uuid);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 直接使用 — consume one card via the upstream activation path. */
    private static void doDirectUse(ServerPlayer player, FactionCardType type) {
        if (!canActivateFactionCard(player, type)) {
            return;
        }
        if (BackpackManager.getCardCount(player, type) < 1) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.card.insufficient"));
            return;
        }
        boolean ok = BackpackManager.activateCard(player, type);
        if (!ok) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.card.use_failed"));
        }
        // Success path (record/daily-use/persist) is handled by BackpackManagerMixin;
        // DailyTaskTracker.onCardUsed fires at match confirmation / draft offer (N-07).
    }

    /** Shared GUI/command entry: durably reserve quota and debit before publishing the force. */
    public static boolean activateFactionCard(ServerPlayer player, FactionCardType type) {
        if (!canActivateFactionCard(player, type) || !LocalBackpackStore.prepareOnline(player)) return false;
        UUID uid = player.getUUID();
        UUID activation = CardActivationJournal.activate(uid, type.questKey.toLowerCase(java.util.Locale.ROOT), null);
        if (activation == null) return false;
        long day = PlayerLotteryStore.get().getOrLoad(uid).lastFactionCardUseEpochDay;
        int roleType = FactionCardRoleTypes.roleTypeId(type);
        ActiveCardForces.restore(uid, roleType, activation, day);
        org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager.ForcePlayerTeam.put(uid, roleType);
        LocalBackpackStore.syncCommitted(player);
        Component message = Component.translatable("message.sre.progression.faction_card_activated",
                Component.translatable(type.displayName));
        player.sendSystemMessage(message);
        player.displayClientMessage(message, true);
        return true;
    }

    /** Consume one independent self-select card only after validating the exact role. */
    private static void doSelfSelect(ServerPlayer player, String roleId) {
        UUID uuid = player.getUUID();
        if (DailySelfSelectService.remaining(uuid) <= 0) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.self_select.daily_limit_reached"));
            return;
        }
        if (SelfSelectForces.has(uuid) || ActiveCardForces.get(uuid) != null || isRoleForceQueued(uuid)) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.self_select.already_active"));
            return;
        }
        ResourceLocation requestedRole = ResourceLocation.tryParse(roleId);
        if (requestedRole != null && !SelfSelectRoleConfigService.isAllowed(requestedRole)) {
            player.sendSystemMessage(Component.translatable("screen.habitrain_lottery.self_select.role_blocked"));
            return;
        }
        SRERole role = resolveCandidate(FactionCardType.NONE, roleId);
        if (role == null || SelfSelectForces.isRoleTaken(role.identifier())) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.self_select.role_unavailable"));
            return;
        }
        if (!consumeSelfSelect(uuid, role.identifier())) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.self_select.consume_failed"));
            return;
        }
        player.sendSystemMessage(Component.translatable(
                "message.habitrain_lottery.self_select.selected", role.getName()));
    }

    /** Called only after the server has resolved and validated the chosen role. */
    static boolean consumeSelfSelect(UUID uuid, ResourceLocation roleId) {
        if (uuid == null || roleId == null || SelfSelectForces.has(uuid)
                || ActiveCardForces.get(uuid) != null || isRoleForceQueued(uuid)
                || CardActivationJournal.hasPending(uuid)
                || SelfSelectForces.isRoleTaken(roleId)
                || !SelfSelectRoleConfigService.isAllowed(roleId)
                || DailySelfSelectService.remaining(uuid) <= 0) return false;
        UUID activation = CardActivationJournal.activate(uuid, SELF_SELECT_KEY, roleId.toString());
        if (activation == null) return false;
        SelfSelectForces.restore(uuid, roleId, activation,
                PlayerLotteryStore.get().getOrLoad(uuid).lastSelfSelectUseEpochDay);
        return true;
    }

    // =====================================================================
    // Candidate computation
    // =====================================================================

    /**
     * Roles eligible for the given card's faction (service-authoritative).
     *
     * <p>Vanilla base roles (killer/vigilante/civilian) are included for self-selection.
     */
    public static List<SRERole> listCandidates(FactionCardType type) {
        List<SRERole> candidates = listUnrestrictedCandidates(type);
        if (type == FactionCardType.NONE) {
            candidates.removeIf(role -> !SelfSelectRoleConfigService.isAllowed(role.identifier()));
        }
        return candidates;
    }

    /** Editor roster before this mod's restrictions, from the same effective API directory. */
    static List<SRERole> listUnrestrictedCandidates(FactionCardType type) {
        List<SRERole> candidates = new ArrayList<>();
        if (type == null) {
            return candidates;
        }
        for (EffectiveRole er : RoleCatalogApi.instance().effectiveRoles(
                RoleQuery.builder().purpose(QueryPurpose.LOTTERY_CARD).build())) {
            SRERole role = er.role();
            if (role == null) {
                continue;
            }
            if (type != FactionCardType.NONE && !matchesCardFaction(type, role)) {
                continue;
            }
            if (role instanceof RepairRole) {
                continue;
            }
            try {
                if (SREDisableManager.isRoleDisabled(role)) {
                    continue;
                }
            } catch (Throwable t) {
                // treat as enabled when the roster is unreachable
            }
            candidates.add(role);
        }
        candidates.sort((a, b) -> {
            boolean aVanilla = Harpymodloader.VANNILA_ROLES.contains(a);
            boolean bVanilla = Harpymodloader.VANNILA_ROLES.contains(b);
            if (aVanilla != bVanilla) {
                return aVanilla ? -1 : 1;
            }
            return a.getName().getString().compareTo(b.getName().getString());
        });
        return candidates;
    }

    /** Re-resolve the chosen roleId against the authoritative candidate list. */
    public static SRERole resolveCandidate(FactionCardType type, String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        ResourceLocation rl;
        try {
            rl = ResourceLocation.parse(roleId);
        } catch (RuntimeException e) {
            return null;
        }
        RoleKey canonical;
        try {
            canonical = RoleCatalogApi.instance().canonicalize(rl);
        } catch (Throwable t) {
            canonical = RoleKey.of(rl);
        }
        for (SRERole role : listCandidates(type)) {
            if (role.identifier().equals(rl)
                    || (canonical != null && canonical.location().equals(role.identifier()))) {
                return role;
            }
        }
        return null;
    }

    // =====================================================================
    // Refund (assignment-time failure)
    // =====================================================================

    /**
     * Refund a self-select that could not be honored: return {@link #SELF_SELECT_COST}
     * cards, restore the one daily use consumed at self-select time. {@code player}
     * may be offline — then the cards are refunded via the local store and daily
     * quota restored; if completely unavailable the entry is just dropped (same as
     * {@code ActiveCardForces}).
     */
    public static void refundSelfSelect(ServerPlayer player, FactionCardType type) {
        if (player != null) {
            refundSelfSelect(player.getUUID(), type);
        }
    }

    /**
     * Offline-safe self-select refund: {@link LocalBackpackStore} + daily quota
     * even when the player is not on the server.
     */
    public static boolean refundSelfSelect(UUID uuid, FactionCardType type) {
        if (uuid == null) return false;
        UUID refundId = SelfSelectForces.refundId(uuid);
        if (CardActivationJournal.contains(uuid, refundId)) {
            SelfSelectForces.markRefundPending(uuid);
            if (!CardActivationJournal.refund(uuid, refundId)) return false;
            SelfSelectForces.remove(uuid);
            ServerPlayer online = findOnline(uuid);
            if (online != null) LocalBackpackStore.syncCommitted(online);
            return true;
        }
        if (refundId != null) {
            long activationDay = SelfSelectForces.activationEpochDay(uuid);
            String receipt = "self-select-refund:" + refundId;
            SelfSelectForces.markRefundPending(uuid);
            if (!LocalBackpackStore.applyOnce(uuid, receipt, next ->
                    next.selfSelectCards = Math.addExact(Math.max(0, next.selfSelectCards), SELF_SELECT_COST))) {
                HabiLotteryMod.LOGGER.error("Self-select refund failed to persist for {}", uuid);
                return false;
            }
            boolean quotaOk = PlayerLotteryStore.get().applyToPlayerWithRollback(uuid, data -> {
                if (data.rewardReceipts.contains(receipt)) {
                    return;
                }
                if (data.lastSelfSelectUseEpochDay == activationDay) {
                    data.selfSelectUsesToday = Math.max(0, data.selfSelectUsesToday - 1);
                }
                data.rewardReceipts.add(receipt);
            });
            if (!quotaOk) {
                return false;
            }
            SelfSelectForces.remove(uuid);
        } else {
            SelfSelectForces.markRefundPending(uuid);
            if (!REFUNDED_SELF_BALANCES.contains(uuid)) {
                if (!LocalBackpackStore.addSelfSelectCards(uuid, SELF_SELECT_COST)) {
                    HabiLotteryMod.LOGGER.error("Self-select refund failed to persist for {}", uuid);
                    return false;
                }
                REFUNDED_SELF_BALANCES.add(uuid);
            }
            if (!DailySelfSelectService.refundSuccessfulUse(uuid)) return false;
            REFUNDED_SELF_BALANCES.remove(uuid);
        }
        ServerPlayer online = findOnline(uuid);
        if (online != null) {
            online.sendSystemMessage(Component.translatable("message.habitrain_lottery.self_select.refunded"));
        }
        return true;
    }

    public static void clearRefundState() {
        REFUNDED_SELF_BALANCES.clear();
    }

    /** Return the actual consumed card once, including normalized upstream refund requests. */
    public static boolean refundForcedCard(UUID uuid) {
        if (uuid == null) return false;
        Integer forcedType = ActiveCardForces.get(uuid);
        if (forcedType == null) return false;
        FactionCardType type = FactionCardRoleTypes.fromRoleTypeId(forcedType);
        if (type == FactionCardType.NONE) return false;
        ActiveCardForces.markRefundPending(uuid);
        ServerPlayer online = findOnline(uuid);
        UUID activation = ActiveCardForces.refundId(uuid);
        if (CardActivationJournal.contains(uuid, activation)) {
            if (!CardActivationJournal.refund(uuid, activation)) return false;
            if (online != null) LocalBackpackStore.syncCommitted(online);
        } else if (!FactionCardRefunds.refund(uuid, type, online)) return false;
        ActiveCardForces.remove(uuid);
        if (online != null) {
            online.sendSystemMessage(Component.translatable(
                    "message.habitrain_lottery.card.refunded", Component.translatable(type.displayName)));
        }
        HabiLotteryMod.LOGGER.info("Faction card refunded once for {}: {}", uuid, type.questKey);
        return true;
    }

    private static ServerPlayer findOnline(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            MinecraftServer server = HabiLotteryMod.getServer();
            if (server == null) {
                return null;
            }
            return server.getPlayerList().getPlayer(uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Shared Request/Confirm gate: running game, spectator, or rest-area.
     * Never falls back to {@code doDirectUse}.
     */
    public static boolean rejectIfNotLobby(ServerPlayer player) {
        if (player == null) {
            return true;
        }
        if (PlayerStateGate.spectatorRestOrDead(player) || isGameRunning(player)) {
            player.sendSystemMessage(CardUseGates.rejectComponent());
            return true;
        }
        return false;
    }

    /**
     * 游戏是否已经离开大厅状态。统一委托给多维度的 {@link ServerMatchGate}（{@code B-13}）。
     */
    public static boolean isGameRunning(ServerPlayer player) {
        return ServerMatchGate.hasActiveMatch(player);
    }

    /**
     * Card eligibility uses catalog flags so v2 ADD roles join the matching faction
     * card. Vigilante is not civilian; killer-neutral is not generic neutral.
     */
    static boolean matchesCardFaction(FactionCardType type, SRERole role) {
        if (type == null || type == FactionCardType.NONE || role == null) {
            return false;
        }
        return switch (type) {
            case CIVILIAN -> role.isInnocent() && !role.isVigilanteTeam()
                    && !role.canUseKiller() && !role.isNeutrals();
            case NEUTRAL -> role.isNeutrals() && !role.isNeutralForKiller();
            case NEUTRAL_FOR_KILLER -> role.isNeutralForKiller();
            case KILLER -> role.isKillerTeam() || role.canUseKiller() || role.isMafiaTeam();
            case NONE -> false;
        };
    }
}
