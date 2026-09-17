package com.habitrain.lottery.backpack;

import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardMutationResult;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardSnapshot;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardStoreStatus;
import io.wifi.starrailexpress.backpack.BackpackManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Server-side source-of-truth adapter for one-player faction-card administration. */
public final class PlayerCardAdminService {
    private PlayerCardAdminService() {
    }

    public interface OnlineCardAccess {
        int count(FactionCardType type);

        void add(FactionCardType type, int delta);

        boolean persist();

        void resend();

        Map<FactionCardType, Integer> cards();

        boolean storageCorrupt();
    }

    public static CardSnapshot snapshotOnline(ServerPlayer player) {
        if (player == null) {
            return new CardSnapshot(CardStoreStatus.MISSING, Map.of());
        }
        CardSnapshot snapshot = snapshotOnline(new ServerOnlineCardAccess(player));
        Map<String, Integer> cards = new LinkedHashMap<>(snapshot.cards());
        cards.put("self_select", LocalBackpackStore.selfSelectCards(player.getUUID()));
        cards.put("limit_break", LocalBackpackStore.limitBreakCards(player.getUUID()));
        return new CardSnapshot(snapshot.status(), PlayerCardAdminModels.orderedCards(cards));
    }

    public static CardSnapshot snapshotOnline(OnlineCardAccess access) {
        if (access == null) {
            return new CardSnapshot(CardStoreStatus.MISSING, Map.of());
        }
        if (access.storageCorrupt()) {
            return new CardSnapshot(CardStoreStatus.CORRUPT, Map.of());
        }
        Map<String, Integer> cards = new LinkedHashMap<>();
        Map<FactionCardType, Integer> live = access.cards();
        for (FactionCardType type : FactionCardType.values()) {
            if (type != FactionCardType.NONE) {
                cards.put(type.questKey.toLowerCase(java.util.Locale.ROOT),
                        Math.max(0, live == null ? 0 : live.getOrDefault(type, 0)));
            }
        }
        return new CardSnapshot(CardStoreStatus.ONLINE_LIVE,
                PlayerCardAdminModels.orderedCards(cards));
    }

    public static CardSnapshot snapshotOffline(UUID uuid) {
        LocalBackpackStore.LoadResult load = LocalBackpackStore.loadResult(uuid);
        if (load.corrupt()) {
            return new CardSnapshot(CardStoreStatus.CORRUPT, Map.of());
        }
        if (load.isMissing()) {
            return new CardSnapshot(CardStoreStatus.MISSING, Map.of());
        }
        return new CardSnapshot(CardStoreStatus.STORED,
                withVirtualCards(load));
    }

    private static Map<String, Integer> withVirtualCards(LocalBackpackStore.LoadResult load) {
        Map<String, Integer> cards = new LinkedHashMap<>(load.cards());
        cards.put("self_select", load.selfSelectCards());
        cards.put("limit_break", load.limitBreakCards());
        return PlayerCardAdminModels.orderedCards(cards);
    }

    public static CardMutationResult mutate(
            MinecraftServer server,
            UUID uuid,
            FactionCardType type,
            CardOperation operation,
            int value
    ) {
        if (uuid == null) {
            return CardMutationResult.failure("无效玩家 UUID", CardStoreStatus.MISSING);
        }
        ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return mutateOnline(new ServerOnlineCardAccess(online), type, operation, value);
        }
        return LocalBackpackStore.mutate(uuid, type, operation, value);
    }

    public static CardMutationResult mutateSelfSelect(UUID uuid, CardOperation operation, int value) {
        return mutateVirtualCard(uuid, operation, value, false);
    }

    public static CardMutationResult mutateLimitBreak(UUID uuid, CardOperation operation, int value) {
        return mutateVirtualCard(uuid, operation, value, true);
    }

    private static CardMutationResult mutateVirtualCard(UUID uuid, CardOperation operation, int value,
                                                       boolean limitBreak) {
        String name = limitBreak ? "破限卡" : "自选卡";
        try {
            if (uuid == null) return CardMutationResult.failure("无效玩家 UUID", CardStoreStatus.MISSING);
            var load = LocalBackpackStore.loadResult(uuid);
            if (load.corrupt()) return CardMutationResult.failure(name + "存档损坏，已拒绝覆盖", CardStoreStatus.CORRUPT);
            int current = limitBreak ? load.limitBreakCards() : load.selfSelectCards();
            int target = PlayerCardMutationPolicy.targetCount(current, operation, value);
            boolean saved = limitBreak ? LocalBackpackStore.setLimitBreakCards(uuid, target)
                    : LocalBackpackStore.setSelfSelectCards(uuid, target);
            if (!saved)
                return CardMutationResult.failure(name + "存档写入失败", CardStoreStatus.MISSING);
            return CardMutationResult.success(CardStoreStatus.STORED, target);
        } catch (IllegalArgumentException e) {
            return CardMutationResult.failure(e.getMessage(), CardStoreStatus.MISSING);
        }
    }

    public static CardMutationResult mutateOnline(
            OnlineCardAccess access,
            FactionCardType type,
            CardOperation operation,
            int value
    ) {
        if (access == null || type == null || type == FactionCardType.NONE) {
            return CardMutationResult.failure("无效在线玩家或角色卡类型", CardStoreStatus.ONLINE_LIVE);
        }
        if (access.storageCorrupt()) {
            return CardMutationResult.failure("角色卡存档损坏，已拒绝覆盖", CardStoreStatus.CORRUPT);
        }
        try {
            int current = Math.max(0, access.count(type));
            int target = PlayerCardMutationPolicy.targetCount(current, operation, value);
            int delta = target - current;
            if (delta != 0) {
                access.add(type, delta);
            }
            if (!access.persist()) {
                if (delta != 0) {
                    access.add(type, -delta);
                }
                return CardMutationResult.failure("在线角色卡持久化失败，已回滚", CardStoreStatus.ONLINE_LIVE);
            }
            access.resend();
            return CardMutationResult.success(CardStoreStatus.ONLINE_LIVE, target);
        } catch (IllegalArgumentException e) {
            return CardMutationResult.failure(e.getMessage(), CardStoreStatus.ONLINE_LIVE);
        } catch (Exception e) {
            return CardMutationResult.failure("在线角色卡修改失败: " + e.getMessage(), CardStoreStatus.ONLINE_LIVE);
        }
    }

    static final class ServerOnlineCardAccess implements OnlineCardAccess {
        private final ServerPlayer player;

        ServerOnlineCardAccess(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public int count(FactionCardType type) {
            return BackpackManager.getCardCount(player, type);
        }

        @Override
        public void add(FactionCardType type, int delta) {
            BackpackManager.addCard(player, type, delta);
        }

        @Override
        public boolean persist() {
            return LocalBackpackStore.saveFromEnumMap(player.getUUID(), BackpackManager.getCards(player));
        }

        @Override
        public void resend() {
            BackpackManager.resend(player);
        }

        @Override
        public Map<FactionCardType, Integer> cards() {
            return BackpackManager.getCards(player);
        }

        @Override
        public boolean storageCorrupt() {
            return LocalBackpackStore.loadResult(player.getUUID()).corrupt();
        }
    }
}
