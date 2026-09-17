package com.habitrain.lottery.grant;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.bridge.EconomyMirror;
import com.habitrain.lottery.config.GrantsConfig;
import com.habitrain.lottery.config.LotteryConfigService;
import com.habitrain.lottery.config.RatesConfig;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class LotteryGrantService {
    private LotteryGrantService() {
    }

    public static void init() {
        // reserved for future scheduled grants
    }

    public static void grant(ServerPlayer player, int amount, String reason) {
        grant(player, amount, reason, true);
    }

    public static void grant(ServerPlayer player, int amount, String reason, boolean dedupe) {
        if (player == null) {
            return;
        }
        grant(player.getUUID(), amount, reason, dedupe, player);
    }

    /** Offline-safe grant: writes {@link PlayerLotteryStore} + flush. Chat/mirror only if online. */
    public static void grant(UUID uuid, int amount, String reason) {
        grant(uuid, amount, reason, true, findOnline(uuid));
    }

    public static void grant(UUID uuid, int amount, String reason, boolean dedupe) {
        grant(uuid, amount, reason, dedupe, findOnline(uuid));
    }

    private static void grant(UUID uuid, int amount, String reason, boolean dedupe, ServerPlayer online) {
        if (uuid == null || amount == 0) {
            return;
        }
        if (PlayerLotteryStore.get().isLoadFailed(uuid)) {
            HabiLotteryMod.LOGGER.error("Skip grant for load-failed {}", uuid);
            return;
        }
        String key = reason == null ? "manual" : reason;
        PlayerLotteryStore store = PlayerLotteryStore.get();
        PlayerLotteryData snapshot = store.getOrLoad(uuid).copy();
        boolean wasDirty = store.isDirty(uuid);
        if (dedupe && !store.tryConsumeGrantKey(uuid, key)) {
            HabiLotteryMod.LOGGER.debug("Skip duplicate grant {} for {}", key, uuid);
            return;
        }
        store.update(uuid, d -> d.lootChance = Math.max(0, d.lootChance + amount));
        boolean ok = store.flush(uuid);
        if (!ok) {
            store.restoreSnapshot(uuid, snapshot, wasDirty);
            HabiLotteryMod.LOGGER.error(
                    "Grant flush failed for {} reason={}; restored snapshot so key can retry",
                    uuid, reason);
            if (online != null) {
                online.sendSystemMessage(Component.literal("§c[抽奖] 发放失败：存档写入失败，请稍后重试"));
            }
            return;
        }
        if (online != null) {
            EconomyMirror.syncChanceAndCoins(online, store.getOrLoad(uuid));
            String msg = amount > 0
                    ? "§a[抽奖] 获得 " + amount + " 次抽奖机会"
                    : "§e[抽奖] 抽奖次数变化 " + amount;
            if (reason != null) {
                msg += "（" + reason + "）";
            }
            online.sendSystemMessage(Component.literal(msg));
            HabiLotteryMod.LOGGER.info("Granted {} lootChance to {} reason={}",
                    amount, online.getGameProfile().getName(), reason);
        } else {
            HabiLotteryMod.LOGGER.info("Granted {} lootChance to offline {} reason={}", amount, uuid, reason);
        }
    }

    public static void grantEvent(ServerPlayer player, String eventId, String mode, String matchKey) {
        if (player == null) {
            return;
        }
        grantEvent(player.getUUID(), eventId, mode, matchKey, player);
    }

    public static void grantEvent(UUID uuid, String eventId, String mode, String matchKey) {
        grantEvent(uuid, eventId, mode, matchKey, findOnline(uuid));
    }

    private static void grantEvent(UUID uuid, String eventId, String mode, String matchKey, ServerPlayer online) {
        GrantsConfig.GrantEvent event = LotteryConfigService.get().getGrants().find(eventId);
        if (event == null || !event.enabled || event.amount == 0) {
            return;
        }
        if (!modeMatches(event, mode)) {
            return;
        }
        RatesConfig rates = LotteryConfigService.get().getRates();
        double mult = rates.modeMultiplier(mode);
        int amount = (int) Math.round(event.amount * mult);
        if (amount == 0) {
            return;
        }
        String key = eventId + ":" + (matchKey == null ? "na" : matchKey);
        grant(uuid, amount, key, true, online);
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

    private static boolean modeMatches(GrantsConfig.GrantEvent event, String mode) {
        if (event.modes == null || event.modes.isEmpty()) {
            return true;
        }
        for (String m : event.modes) {
            if ("*".equals(m) || (mode != null && m.equalsIgnoreCase(mode))) {
                return true;
            }
        }
        return false;
    }

    /** Apply draw cost multiplier (minimum 1 per successful roll path). */
    public static int computeDrawCost() {
        double m = LotteryConfigService.get().getRates().drawCostMultiplier;
        int cost = (int) Math.round(m);
        return Math.max(1, cost);
    }

    /**
     * C13: SRE draws pass {@code -1}; only that token becomes {@code -cost} while takeover is on.
     * Command {@code -N} and every delta while takeover is off pass through unchanged.
     */
    public static int scaleChanceDelta(int amount, boolean takeover, int cost) {
        if (!takeover || amount != -1) {
            return amount;
        }
        return -Math.max(1, Math.abs(cost));
    }

    public static boolean canAfford(int lootChance) {
        return lootChance >= computeDrawCost();
    }

    public static int applyDuplicateCoin(int baseCoins) {
        RatesConfig rates = LotteryConfigService.get().getRates();
        int flat = rates.duplicateCoinFlat();
        if (flat > 0) {
            return flat;
        }
        double m = rates.duplicateCoinMultiplier;
        return Math.max(0, (int) Math.round(baseCoins * m));
    }
}
