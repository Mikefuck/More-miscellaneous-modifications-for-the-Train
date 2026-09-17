package com.habitrain.lottery.grant;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.bridge.EconomyMirror;
import com.habitrain.lottery.config.LotteryConfigService;
import com.habitrain.lottery.storage.LotteryHistoryStore;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.habitrain.lottery.network.OpenCoinExchangeS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.agmas.noellesroles.packet.Loot.LootDataRefreshS2CPacket;

/**
 * Converts coins into loot chance (official LootInfoScreen coin2lottery path).
 */
public final class CoinToDrawService {
    private CoinToDrawService() {
    }

    /**
     * @return 1 on success, 0 on failure (message already sent)
     */
    public static int tryBuy(ServerPlayer player) {
        return tryBuy(player, 1);
    }

    public static int openScreen(ServerPlayer player) {
        if (!canExchange(player)) return 0;
        if (!ServerPlayNetworking.canSend(player, OpenCoinExchangeS2C.TYPE)) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 请更新客户端抽奖补齐模组以使用兑换页面"));
            return 0;
        }
        var store = PlayerLotteryStore.get();
        ServerPlayNetworking.send(player, new OpenCoinExchangeS2C(store.getCoinNum(player.getUUID()),
                store.getLootChance(player.getUUID()), LotteryConfigService.get().getRates().coinPerDraw()));
        return 1;
    }

    private static boolean canExchange(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (PlayerStateGate.spectatorRestOrDead(player)) {
            player.sendSystemMessage(Component.literal(PlayerStateGate.DRAW_BLOCKED));
            return false;
        }
        if (!PlayerLotteryStore.get().isTakeoverActive()) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 经济系统尚未就绪"));
            return false;
        }
        PlayerLotteryStore.get().getOrLoad(player);
        if (PlayerLotteryStore.get().isLoadFailed(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 玩家数据读取失败，无法兑换"));
            return false;
        }
        return true;
    }

    public static int tryBuy(ServerPlayer player, int amount) {
        if (!canExchange(player)) return 0;
        if (amount <= 0) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 兑换数量必须大于 0"));
            return 0;
        }
        long totalCost = (long) LotteryConfigService.get().getRates().coinPerDraw() * amount;
        int coins = PlayerLotteryStore.get().getCoinNum(player.getUUID());
        if (coins < totalCost) {
            player.sendSystemMessage(Component.literal(
                    "§c[抽奖] 金币不足：需要 " + totalCost + "，当前 " + coins));
            return 0;
        }
        if ((long) PlayerLotteryStore.get().getLootChance(player.getUUID()) + amount > Integer.MAX_VALUE) {
            player.sendSystemMessage(Component.literal("§c[抽奖] 兑换后抽数超过上限"));
            return 0;
        }
        int cost = (int) totalCost;
        PlayerLotteryStore.get().update(player.getUUID(), d -> {
            d.coinNum -= cost;
            d.lootChance += amount;
        });
        try {
            LotteryHistoryStore.get().append(
                    player.getUUID(),
                    -1,
                    -1,
                    "coin2lottery",
                    "coin_to_draw",
                    cost,
                    PlayerLotteryStore.get().getLootChance(player.getUUID())
            );
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("history append failed: {}", t.toString());
        }
        PlayerLotteryStore.get().flush(player.getUUID());
        EconomyMirror.syncChanceAndCoins(player, PlayerLotteryStore.get().getOrLoad(player));
        ServerPlayNetworking.send(player, new LootDataRefreshS2CPacket(
                PlayerLotteryStore.get().getCoinNum(player.getUUID()),
                PlayerLotteryStore.get().getLootChance(player.getUUID())));
        player.sendSystemMessage(Component.literal(
                "§a[抽奖] 已用 " + cost + " 金币兑换 " + amount + " 次抽卡（剩余金币 "
                        + PlayerLotteryStore.get().getCoinNum(player.getUUID())
                        + "，抽数 "
                        + PlayerLotteryStore.get().getLootChance(player.getUUID())
                        + "）"));
        return 1;
    }
}
