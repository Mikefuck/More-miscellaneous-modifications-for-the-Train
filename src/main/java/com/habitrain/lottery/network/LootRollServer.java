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
        Pair<Integer, Integer> result = null;
        boolean rollFailed = false;
        try {
            store.addLootChance(uuid, -cost);
            result = pool.rollOnce(player);
            if (LootRollTransaction.isValidRoll(result == null ? null : result.first)) {
                LotteryHistoryStore.get().append(
                        uuid, poolId, result.first, "", "chance_delta", 0, store.getLootChance(uuid));
            }
        } catch (Exception e) {
            rollFailed = true;
            HabiLotteryMod.LOGGER.warn("loot single roll failed: {}", e.toString());
        } finally {
            store.endDeferredFlush();
        }

        if (rollFailed) {
            // 审核 B-04：异常路径也必须先落盘再决定是否/如何通知玩家。
            if (!restoreEconomySnapshot(player, snap)) {
                player.sendSystemMessage(Component.literal(
                        "§c[抽奖] 存档写入失败，本次抽奖未完成；若重启后数据异常请联系管理员"));
                return;
            }
            player.sendSystemMessage(Component.literal("§c[抽奖] 本次抽奖失败，抽数已退回"));
            return;
        }

        boolean validRoll = LootRollTransaction.isValidRoll(result == null ? null : result.first);
        if (!validRoll) {
            refundAttemptIfNeeded(player, snap, false);
            return;
        }

        // 审核 B-04：**先落盘、后发货**。旧实现在这里就把结果发给了客户端，
        // 而 flush 的返回值被丢弃——磁盘满 / 世界只读时玩家看到奖励却什么也没持久化，
        // 重启后整笔凭空消失。现在 flush 失败即回滚内存并明确告知玩家失败。
        if (!store.flush(uuid)) {
            PlayerLotteryData after = store.getOrLoad(uuid);
            boolean awarded = LootRollTransaction.awardPersisted(snap, after);
            if (!awarded) {
                refundAttemptIfNeeded(player, snap, true, awarded);
            }
            HabiLotteryMod.LOGGER.error(
                    "loot single roll: flush failed for {} (pool {}) — reward withheld", uuid, poolId);
            player.sendSystemMessage(Component.literal(
                    "§c[抽奖] 存档写入失败，本次抽奖未生效"
                            + (awarded ? "（奖励已尝试落盘，请重启后核对）" : "，抽数已退回")));
            return;
        }

        sendEconomyRefresh(player);
        ServerPlayNetworking.send(player, new LootResultS2CPacket(poolId, result.first, result.second));
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
        List<int[]> results = new ArrayList<>();
        int refundedRolls = 0;
        boolean rollFailed = false;
        try {
            store.addLootChance(uuid, -affordableRolls * cost);
            for (int i = 0; i < affordableRolls; i++) {
                Pair<Integer, Integer> result = pool.rollOnce(player);
                if (LootRollTransaction.isValidRoll(result == null ? null : result.first)) {
                    results.add(new int[]{result.first, result.second});
                } else {
                    // 无效结果立即退还该次抽数（内存），落盘在 endDeferredFlush 之后统一做。
                    refundedRolls++;
                }
            }
            if (refundedRolls > 0) {
                store.addLootChance(uuid, refundedRolls * cost);
            }
            if (!results.isEmpty()) {
                int chanceAfter = store.getLootChance(uuid);
                for (int[] row : results) {
                    LotteryHistoryStore.get().append(
                            uuid, poolId, row[0], "", "chance_delta", 0, chanceAfter);
                }
            }
        } catch (Exception e) {
            rollFailed = true;
            HabiLotteryMod.LOGGER.warn("loot multi roll failed: {}", e.toString());
        } finally {
            store.endDeferredFlush();
        }

        if (rollFailed) {
            // 审核 B-04：回滚自身的 flush 也必须检查——回滚失败会让磁盘领先内存。
            if (!restoreEconomySnapshot(player, batchSnap)) {
                player.sendSystemMessage(Component.literal(
                        "§c[抽奖] 存档写入失败，本次连抽未完成；若重启后数据异常请联系管理员"));
                return;
            }
            player.sendSystemMessage(Component.literal("§c[抽奖] 本次连抽失败，抽数已退回"));
            return;
        }

        // 审核 B-04：先落盘、后发货。旧实现把结果先发给客户端、丢弃 flush 结果，
        // 于是「客户端看到奖励、磁盘上什么都没有」，重启后整笔消失。
        if (!store.flush(uuid)) {
            PlayerLotteryData after = store.getOrLoad(uuid);
            boolean awarded = LootRollTransaction.awardPersisted(batchSnap, after);
            if (!awarded) {
                restoreEconomySnapshot(player, batchSnap);
            }
            HabiLotteryMod.LOGGER.error(
                    "loot multi roll: flush failed for {} (pool {}, {} rolls) — result withheld",
                    uuid, poolId, results.size());
            player.sendSystemMessage(Component.literal(
                    "§c[抽奖] 存档写入失败，本次连抽未生效"
                            + (awarded ? "（奖励已尝试落盘，请重启后核对）" : "，抽数已退回")));
            return;
        }

        sendEconomyRefresh(player);
        if (!results.isEmpty()) {
            ServerPlayNetworking.send(player, new LootMultiResultS2CPacket(poolId, results));
        }
    }

    /**
     * Refund the pre-debit only when the attempt did not persist a skin/coin award.
     * Successful or already-flushed awards keep the debit.
     *
     * <p>审核 B-04：本方法过去既不检查 {@code restoreEconomySnapshot} 内部的 flush，
     * 也不返回结果，调用方无从判断回滚是否真的落地。现在返回「回滚后的状态是否已持久化」。
     */
    private static boolean refundAttemptIfNeeded(ServerPlayer player, PlayerLotteryData snap, boolean validRoll) {
        PlayerLotteryData after = PlayerLotteryStore.get().getOrLoad(player.getUUID());
        boolean awarded = LootRollTransaction.awardPersisted(snap, after);
        return refundAttemptIfNeeded(player, snap, validRoll, awarded);
    }

    /** 审核 B-04：{@code awarded} 已由调用方算好时不要重复扫描快照。 */
    private static boolean refundAttemptIfNeeded(ServerPlayer player, PlayerLotteryData snap,
                                                 boolean validRoll, boolean awarded) {
        if (!LootRollTransaction.shouldRefundChance(validRoll, awarded)) {
            return true;
        }
        return restoreEconomySnapshot(player, snap);
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

    /**
     * 把内存状态回滚到 {@code snap} 并落盘。
     *
     * <p><b>审核 B-04</b>：本方法过去在收尾处调用 {@code store.flush(uuid)} 却丢弃返回值。
     * 回滚本身也可能写盘失败——那时<b>磁盘会领先内存</b>（下次启动会把「已回滚但没写成功」
     * 的旧状态读回来），调用方必须知道这一点才能给玩家正确的提示，因此现在返回
     * 「回滚后的状态是否已成功持久化」。
     *
     * @return {@code true} 表示回滚后的状态已确定落盘（或本来无需写盘）
     */
    private static boolean restoreEconomySnapshot(ServerPlayer player, PlayerLotteryData snap) {
        if (player == null || snap == null) {
            return true;
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
        boolean ok = store.flush(uuid);
        if (!ok) {
            HabiLotteryMod.LOGGER.error(
                    "loot roll rollback: flush failed for {} — on-disk state may be ahead of memory", uuid);
        }
        return ok;
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
