package com.habitrain.lottery.card;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.DailyFactionCardService;
import com.habitrain.lottery.backpack.DailySelfSelectService;
import com.habitrain.lottery.backpack.PlayerCardAdminService;
import com.habitrain.lottery.backpack.FactionCardRoleTypes;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.backpack.LimitBreakCardService;
import com.habitrain.core.api.match.MatchStateApi;
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
import net.minecraft.server.level.ServerLevel;
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
                    player.sendSystemMessage(Component.literal("§c[突破上限卡] 卡牌不足、今日额外次数已满，或存档不可写"));
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
            player.sendSystemMessage(Component.literal("§c[职业卡] 使用失败: " + t.getMessage()));
        }
    }

    /** 直接使用 — consume one card via the upstream activation path. */
    private static void doDirectUse(ServerPlayer player, FactionCardType type) {
        if (DailyFactionCardService.hasReachedLimit(player)) {
            player.sendSystemMessage(Component.literal(
                    "§c[职业卡] 今日使用次数已达上限，可使用突破上限卡增加 1 次"));
            return;
        }
        if (BackpackManager.getCardCount(player, type) < 1) {
            player.sendSystemMessage(Component.literal("§c[职业卡] 该角色卡数量不足"));
            return;
        }
        if (SelfSelectForces.has(player.getUUID()) || ActiveCardForces.get(player.getUUID()) != null || RoleForceApi.instance().isQueued(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c[职业卡] 已有生效的职业卡，本局不能再使用"));
            return;
        }
        boolean ok = BackpackManager.activateCard(player, type);
        if (!ok) {
            player.sendSystemMessage(Component.literal("§c[职业卡] 使用失败"));
        }
        // Success path (record/daily-use/persist) is handled by BackpackManagerMixin.
    }

    /** Consume one independent self-select card only after validating the exact role. */
    private static void doSelfSelect(ServerPlayer player, String roleId) {
        UUID uuid = player.getUUID();
        if (DailySelfSelectService.remaining(uuid) <= 0) {
            player.sendSystemMessage(Component.literal("§c[自选卡] 今日自选次数已用完"));
            return;
        }
        if (SelfSelectForces.has(uuid) || ActiveCardForces.get(uuid) != null || RoleForceApi.instance().isQueued(uuid)) {
            player.sendSystemMessage(Component.literal("§c[自选卡] 下局已有生效的卡牌"));
            return;
        }
        SRERole role = resolveCandidate(FactionCardType.NONE, roleId);
        if (role == null || SelfSelectForces.isRoleTaken(role.identifier())) {
            player.sendSystemMessage(Component.literal("§c[自选卡] 角色无效或已被其他玩家自选"));
            return;
        }
        if (!consumeSelfSelect(uuid, role.identifier())) {
            player.sendSystemMessage(Component.literal("§c[自选卡] 卡牌或次数不足，或存档写入失败，未激活；存档异常请联系管理员"));
            return;
        }
        player.sendSystemMessage(Component.literal("§a[自选卡] 已自选 " + role.getName().getString()
                + "，消耗 1 张自选卡；下局名额不足时退还"));
    }

    /** Called only after the server has resolved and validated the chosen role. */
    static boolean consumeSelfSelect(UUID uuid, ResourceLocation roleId) {
        if (uuid == null || roleId == null || SelfSelectForces.has(uuid)
                || ActiveCardForces.get(uuid) != null || SelfSelectForces.isRoleTaken(roleId)
                || DailySelfSelectService.remaining(uuid) <= 0) return false;
        if (!LocalBackpackStore.addSelfSelectCards(uuid, -SELF_SELECT_COST)) return false;
        if (!DailySelfSelectService.recordSuccessfulUse(uuid)) {
            if (!LocalBackpackStore.addSelfSelectCards(uuid, SELF_SELECT_COST)) {
                HabiLotteryMod.LOGGER.error("Self-select debit rollback failed for {}", uuid);
            }
            return false;
        }
        SelfSelectForces.record(uuid, roleId);
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
        ServerPlayer online = findOnline(uuid);
        if (online != null) {
            online.sendSystemMessage(Component.literal("§e[自选卡] 自选未能生效，已退还 1 张自选卡及自选次数"));
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
        if (online != null) {
            // Bypass ProgressionDataManager: its refund hook delegates here.
            BackpackManager.addCard(online, type, 1);
            ActiveCardForces.remove(uuid);
            try {
                if (!LocalBackpackStore.saveFromEnumMap(uuid, BackpackManager.getCards(online))) {
                    HabiLotteryMod.LOGGER.error("Forced-card refund persist failed for {}", uuid);
                }
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.error("Forced-card refund persist threw for {}", uuid, t);
            }
        } else {
            if (!LocalBackpackStore.addCards(uuid, type, 1)) {
                HabiLotteryMod.LOGGER.error("Offline forced-card refund failed to persist backpack for {}", uuid);
                return false;
            }
            ActiveCardForces.remove(uuid);
        }
        DailyFactionCardService.refundSuccessfulUse(uuid);
        if (online != null) {
            online.sendSystemMessage(Component.literal("§e[职业卡] 阵营卡未兑现，已退还 1 张")
                    .append(Component.translatable(type.displayName))
                    .append(Component.literal("及 1 次使用次数")));
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
    private static boolean rejectIfNotLobby(ServerPlayer player) {
        boolean spectator = false;
        try {
            spectator = player.isSpectator();
        } catch (Throwable ignored) {
        }
        boolean resting = com.habitrain.lottery.bridge.RestAreaStateBridge.isResting(player);
        boolean dead = false;
        try {
            dead = !player.isAlive();
        } catch (Throwable ignored) {
        }
        if (CardUseGates.shouldReject(spectator, resting, dead, isGameRunning(player))) {
            player.sendSystemMessage(Component.literal(CardUseGates.REJECT_MESSAGE));
            return true;
        }
        return false;
    }

    /**
     * 游戏是否已经离开大厅状态。与客户端 {@code CardGuiGameState} 一致：只要
     * {@code gameStatus != INACTIVE}（含 STARTING 开场过渡）即拒绝，避免在开局
     * 瞬间提交的选择生效。
     */
    private static boolean isGameRunning(ServerPlayer player) {
        try {
            MinecraftServer server = player == null ? null : player.getServer();
            if (server == null) {
                boolean notInactive = MatchStateApi.hasLeftLobby(player == null ? null : player.level());
                return CardUseGates.gameNotInactive(false, notInactive);
            }
            java.util.ArrayList<Boolean> flags = new java.util.ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                flags.add(MatchStateApi.hasLeftLobby(level));
            }
            boolean[] perLevel = new boolean[flags.size()];
            for (int i = 0; i < flags.size(); i++) {
                perLevel[i] = Boolean.TRUE.equals(flags.get(i));
            }
            return CardUseGates.gameNotInactive(false, CardUseGates.anyLevelNotInactive(perLevel));
        } catch (Throwable t) {
            return CardUseGates.gameNotInactive(true, false);
        }
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
