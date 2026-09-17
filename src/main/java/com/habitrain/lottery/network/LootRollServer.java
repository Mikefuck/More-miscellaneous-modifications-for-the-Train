package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.bridge.EconomyMirror;
import com.habitrain.lottery.config.LotteryConfigService;
import com.habitrain.lottery.config.PoolConfigModels;
import com.habitrain.lottery.grant.LootRollTransaction;
import com.habitrain.lottery.grant.LootBatchPolicy;
import com.habitrain.lottery.grant.LotteryGrantService;
import com.habitrain.lottery.storage.LotteryHistoryStore;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.data.PlayerEconomyManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.noellesroles.packet.Loot.LootDataRefreshC2SPacket;
import org.agmas.noellesroles.packet.Loot.LootDataRefreshS2CPacket;
import org.agmas.noellesroles.packet.Loot.LootMultiRequestC2SPacket;
import org.agmas.noellesroles.packet.Loot.LootMultiResultS2CPacket;
import org.agmas.noellesroles.packet.Loot.LootRequestC2SPacket;
import org.agmas.noellesroles.packet.Loot.LootResultS2CPacket;
import org.agmas.noellesroles.utils.Pair;
import org.agmas.noellesroles.utils.lottery.LotteryManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-enables the SRE gacha draw flow that upstream disabled (all Loot C2S handlers
 * were replaced with empty no-ops in {@code RiceReceiverRegister}).
 * <p>
 * Fabric global receivers are first-registration-wins, so SRE's empty handlers would
 * win if we only called {@code registerGlobalReceiver}. We therefore unregister the
 * channel first, then register the real handler. Registration is deferred to
 * {@code ServerLifecycleEvents.SERVER_STARTING} (see {@code LotteryNetwork}), by which
 * point every mod's init has run, so SRE's payload types are registered and its empty
 * stub receivers exist for the unregister to hit.
 */
public final class LootRollServer {
    private static final ConcurrentHashMap<UUID, Object> ROLL_LOCKS = new ConcurrentHashMap<>();

    private LootRollServer() {
    }

    public static void register() {
        registerSingle();
        registerMulti();
        registerRefresh();
    }

    private static Object lockFor(UUID uuid) {
        return ROLL_LOCKS.computeIfAbsent(uuid, id -> new Object());
    }

    private static void registerSingle() {
        ServerPlayNetworking.unregisterGlobalReceiver(LootRequestC2SPacket.ID.id());
        ServerPlayNetworking.registerGlobalReceiver(LootRequestC2SPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                synchronized (lockFor(player.getUUID())) {
                    if (!PlayerLotteryStore.get().isTakeoverActive()) {
                        player.sendSystemMessage(Component.literal("§c[抽奖] 经济接管未就绪，拒绝抽奖"));
                        return;
                    }
                    handleSingle(player, payload.poolID());
                }
            });
        });
    }

    private static void registerMulti() {
        ServerPlayNetworking.unregisterGlobalReceiver(LootMultiRequestC2SPacket.ID.id());
        ServerPlayNetworking.registerGlobalReceiver(LootMultiRequestC2SPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                synchronized (lockFor(player.getUUID())) {
                    if (!PlayerLotteryStore.get().isTakeoverActive()) {
                        player.sendSystemMessage(Component.literal("§c[抽奖] 经济接管未就绪，拒绝抽奖"));
                        return;
                    }
                    handleMulti(player, payload.poolID(), payload.count());
                }
            });
        });
    }

    private static void registerRefresh() {
        ServerPlayNetworking.unregisterGlobalReceiver(LootDataRefreshC2SPacket.ID.id());
        ServerPlayNetworking.registerGlobalReceiver(LootDataRefreshC2SPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null || LotteryNetwork.rateLimited(player, "loot_refresh", 400)) {
                    return;
                }
                sendEconomyRefresh(player);
            });
        });
    }

    private static void handleSingle(ServerPlayer player, int poolId) {
        if (LotteryNetwork.rateLimited(player, "loot_roll", 400)) {
            return;
        }
        if (PlayerStateGate.spectatorRestOrDead(player)) {
            return;
        }
        LotteryManager.LotteryPool pool = resolveEnabledPool(poolId);
        if (pool == null) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 抽数不足或卡池不存在"));
            return;
        }
        UUID uuid = player.getUUID();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 存档不可读"));
            return;
        }
        int cost = LotteryGrantService.computeDrawCost();
        if (!LotteryGrantService.canAfford(store.getLootChance(uuid))) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 抽数不足"));
            return;
        }
        store.beginDeferredFlush();
        PlayerLotteryData snap = store.getOrLoad(uuid).copy();
        try {
            store.addLootChance(uuid, -cost);
            Pair<Integer, Integer> result = pool.rollOnce(player);
            boolean valid = LootRollTransaction.isValidRoll(result == null ? null : result.first);
            if (!valid) {
                refundAttemptIfNeeded(player, snap, false);
                return;
            }
            LotteryHistoryStore.get().append(
                    uuid, poolId, result.first, "", "chance_delta", 0, store.getLootChance(uuid));
            sendEconomyRefresh(player);
            ServerPlayNetworking.send(player, new LootResultS2CPacket(poolId, result.first, result.second));
        } catch (Exception e) {
            refundAttemptIfNeeded(player, snap, false);
            HabiLotteryMod.LOGGER.warn("loot single roll failed: {}", e.toString());
        } finally {
            store.endDeferredFlush();
            store.flush(uuid);
        }
    }

    private static void handleMulti(ServerPlayer player, int poolId, int requested) {
        if (LotteryNetwork.rateLimited(player, "loot_roll", 400)) {
            return;
        }
        if (PlayerStateGate.spectatorRestOrDead(player)) {
            return;
        }
        if (requested < 1) {
            return;
        }
        LotteryManager.LotteryPool pool = resolveEnabledPool(poolId);
        if (pool == null) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 卡池不存在"));
            return;
        }
        UUID uuid = player.getUUID();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (store.isLoadFailed(uuid)) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 存档不可读"));
            return;
        }
        int cost = LotteryGrantService.computeDrawCost();
        int chance = store.getLootChance(uuid);
        int affordableRolls = LootBatchPolicy.affordableRolls(requested, chance, cost);
        if (affordableRolls < 1) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 抽数不足"));
            return;
        }
        store.beginDeferredFlush();
        PlayerLotteryData batchSnap = store.getOrLoad(uuid).copy();
        store.addLootChance(uuid, -affordableRolls * cost);
        List<int[]> results = new ArrayList<>();
        try {
            for (int i = 0; i < affordableRolls; i++) {
                Pair<Integer, Integer> result = pool.rollOnce(player);
                if (LootRollTransaction.isValidRoll(result == null ? null : result.first)) {
                    results.add(new int[]{result.first, result.second});
                } else {
                    store.addLootChance(uuid, cost);
                }
            }
            sendEconomyRefresh(player);
            if (!results.isEmpty()) {
                int chanceAfter = store.getLootChance(uuid);
                for (int[] row : results) {
                    LotteryHistoryStore.get().append(
                            uuid, poolId, row[0], "", "chance_delta", 0, chanceAfter);
                }
                ServerPlayNetworking.send(player, new LootMultiResultS2CPacket(poolId, results));
            }
        } catch (Exception e) {
            restoreEconomySnapshot(player, batchSnap);
            HabiLotteryMod.LOGGER.warn("loot multi roll failed: {}", e.toString());
        } finally {
            store.endDeferredFlush();
            store.flush(uuid);
        }
    }

    /**
     * Refund the pre-debit only when the attempt did not persist a skin/coin award.
     * Successful or already-flushed awards keep the debit.
     */
    private static void refundAttemptIfNeeded(ServerPlayer player, PlayerLotteryData snap, boolean validRoll) {
        PlayerLotteryData after = PlayerLotteryStore.get().getOrLoad(player.getUUID());
        boolean awarded = LootRollTransaction.awardPersisted(snap, after);
        if (!LootRollTransaction.shouldRefundChance(validRoll, awarded)) {
            return;
        }
        restoreEconomySnapshot(player, snap);
    }

    private static LotteryManager.LotteryPool resolveEnabledPool(int poolId) {
        if (!isEnabledPool(poolId)) {
            return null;
        }
        try {
            return LotteryManager.getInstance().getLotteryPool(poolId);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("loot pool lookup failed: {}", e.toString());
            return null;
        }
    }

    private static boolean isEnabledPool(int poolId) {
        PoolConfigModels.Root root = LotteryConfigService.get().getPools();
        if (root == null || root.Pools == null) {
            return false;
        }
        for (PoolConfigModels.Pool p : root.Pools) {
            if (p != null && p.PoolID == poolId) {
                return p.Enable;
            }
        }
        return false;
    }

    private static void restoreEconomySnapshot(ServerPlayer player, PlayerLotteryData snap) {
        if (player == null || snap == null) {
            return;
        }
        UUID uuid = player.getUUID();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData cur = store.getOrLoad(uuid);
        List<String[]> extras = new ArrayList<>();
        if (cur.unlocked != null) {
            for (Map.Entry<String, Map<String, Boolean>> typeEntry : cur.unlocked.entrySet()) {
                Map<String, Boolean> skins = typeEntry.getValue();
                if (skins == null) {
                    continue;
                }
                Map<String, Boolean> snapType = snap.unlocked == null ? null : snap.unlocked.get(typeEntry.getKey());
                for (Map.Entry<String, Boolean> skinEntry : skins.entrySet()) {
                    if (!Boolean.TRUE.equals(skinEntry.getValue())) {
                        continue;
                    }
                    if (snapType == null || !Boolean.TRUE.equals(snapType.get(skinEntry.getKey()))) {
                        extras.add(new String[]{typeEntry.getKey(), skinEntry.getKey()});
                    }
                }
            }
        }
        store.update(uuid, d -> {
            d.lootChance = snap.lootChance;
            d.coinNum = snap.coinNum;
            d.unlocked.clear();
            if (snap.unlocked != null) {
                snap.unlocked.forEach((k, v) -> d.unlocked.put(k, v == null ? new HashMap<>() : new HashMap<>(v)));
            }
        });
        for (String[] extra : extras) {
            EconomyMirror.lockCca(player, extra[0], extra[1]);
            EconomyMirror.runSuppressed(() ->
                    PlayerEconomyManager.lockSkinForItemType(player, extra[0], extra[1]));
        }
        EconomyMirror.syncChanceAndCoins(player, store.getOrLoad(uuid));
        store.flush(uuid);
    }

    /** Sends current coin/draw values so an open LootInfoScreen re-renders accurately. */
    private static void sendEconomyRefresh(ServerPlayer player) {
        try {
            ServerPlayNetworking.send(player, new LootDataRefreshS2CPacket(
                    PlayerEconomyManager.getCoinNum(player),
                    PlayerEconomyManager.getLootChance(player)));
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("loot economy refresh failed: {}", e.toString());
        }
    }
}
