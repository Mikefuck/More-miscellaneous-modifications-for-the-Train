package com.habitrain.lottery.bridge;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryData;
import io.wifi.starrailexpress.cca.SREPlayerSkinsComponent;
import io.wifi.starrailexpress.data.PlayerEconomyManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * Mirrors chance and coins for the upstream lottery interface. Skin state uses SkinNetwork.
 * Economy destinations:
 * <ul>
 *   <li>{@link PlayerEconomyManager} (lottery roll path)</li>
 *   <li>{@link SREPlayerSkinsComponent} (legacy currency transport)</li>
 * </ul>
 *
 * World JSON under {@code {world}/habitrain_lottery/players/} is the only durable store.
 */
public final class EconomyMirror {
    private static final ThreadLocal<Boolean> SUPPRESS = ThreadLocal.withInitial(() -> false);

    private EconomyMirror() {
    }

    public static boolean isSuppressed() {
        return Boolean.TRUE.equals(SUPPRESS.get());
    }

    public static void runSuppressed(Runnable r) {
        boolean prev = isSuppressed();
        SUPPRESS.set(true);
        try {
            r.run();
        } finally {
            SUPPRESS.set(prev);
        }
    }

    /**
     * Reconciles SRE memory + CCA from world data for join/admin import without destructive wipe.
     *
     * @return {@code false} if the reconcile threw; callers must not treat JOIN as COMMIT.
     */
    public static boolean pushToSre(ServerPlayer player, PlayerLotteryData data) {
        return pushToSre(player, data, true);
    }

    public static boolean pushToSre(ServerPlayer player, PlayerLotteryData data, boolean pushChanceCoins) {
        if (player == null || data == null) {
            return false;
        }
        boolean[] ok = {false};
        runSuppressed(() -> {
            int unlockCount = 0;
            if (data.unlocked != null) {
                for (Map<String, Boolean> m : data.unlocked.values()) {
                    if (m != null) unlockCount += m.size();
                }
            }
            int equippedCount = data.equipped != null ? data.equipped.size() : 0;
            HabiLotteryMod.LOGGER.info("pushToSre BEGIN for {} (chance={}, coins={}, unlocks={}, equipped={}, pushEconomy={})",
                    player.getGameProfile().getName(), data.lootChance, data.coinNum, unlockCount, equippedCount, pushChanceCoins);
            try {
                if (pushChanceCoins) {
                    int chanceDelta = data.lootChance - PlayerEconomyManager.getLootChance(player);
                    int coinDelta = data.coinNum - PlayerEconomyManager.getCoinNum(player);
                    if (chanceDelta != 0) PlayerEconomyManager.addLootChance(player, chanceDelta);
                    if (coinDelta != 0) PlayerEconomyManager.addCoinNum(player, coinDelta);
                    mirrorChanceCoinsToCca(player, data);
                }
                com.habitrain.lottery.skin.SkinNetwork.sync(player);
                InventorySkinApplier.applyAllEquipped(player, data.equipped);
                syncCca(player);
                ok[0] = true;
                HabiLotteryMod.LOGGER.info("pushToSre COMMIT for {}", player.getGameProfile().getName());
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.error("pushToSre failed for {}", player.getGameProfile().getName(), e);
            }
        });
        return ok[0];
    }

    /** PEM/CCA chance or coins are non-zero (read without takeover mixins). */
    public static boolean sreChanceOrCoinsNonZero(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        boolean[] nonZero = {false};
        runSuppressed(() -> {
            try {
                if (PlayerEconomyManager.getLootChance(player) != 0 || PlayerEconomyManager.getCoinNum(player) != 0) {
                    nonZero[0] = true;
                    return;
                }
                SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
                if (c != null) {
                    Integer ch = c.getLootChance();
                    Integer co = c.getCoinNum();
                    if ((ch != null && ch != 0) || (co != null && co != 0)) {
                        nonZero[0] = true;
                    }
                }
            } catch (Exception ignored) {
            }
        });
        return nonZero[0];
    }

    public static void copyChanceCoinsFromSre(ServerPlayer player, PlayerLotteryData data) {
        if (player == null || data == null) {
            return;
        }
        runSuppressed(() -> {
            try {
                data.lootChance = Math.max(0, PlayerEconomyManager.getLootChance(player));
                data.coinNum = Math.max(0, PlayerEconomyManager.getCoinNum(player));
                SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
                if (c != null) {
                    if (c.getLootChance() != null) {
                        data.lootChance = Math.max(data.lootChance, c.getLootChance());
                    }
                    if (c.getCoinNum() != null) {
                        data.coinNum = Math.max(data.coinNum, c.getCoinNum());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Incremental sync for chance and coins only.
     */
    public static void syncChanceAndCoins(ServerPlayer player, PlayerLotteryData data) {
        if (player == null || data == null) {
            return;
        }
        runSuppressed(() -> {
            try {
                int curChance = PlayerEconomyManager.getLootChance(player);
                int deltaChance = data.lootChance - curChance;
                if (deltaChance != 0) {
                    PlayerEconomyManager.addLootChance(player, deltaChance);
                }
                int curCoins = PlayerEconomyManager.getCoinNum(player);
                int deltaCoins = data.coinNum - curCoins;
                if (deltaCoins != 0) {
                    PlayerEconomyManager.addCoinNum(player, deltaCoins);
                }
                mirrorChanceCoinsToCca(player, data);
                syncCca(player);
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.debug("syncChanceAndCoins failed: {}", t.toString());
            }
        });
    }

    public static void syncCca(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
            if (c == null) {
                return;
            }
            try {
                c.syncSkinsToClient();
            } catch (Throwable ignored) {
                c.sync();
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("syncCca failed: {}", t.toString());
        }
    }

    private static void mirrorChanceCoinsToCca(ServerPlayer player, PlayerLotteryData data) {
        try {
            SREPlayerSkinsComponent c = SREPlayerSkinsComponent.KEY.get(player);
            if (c == null) {
                return;
            }
            Integer curChance = c.getLootChance();
            int wantChance = data.lootChance;
            if (curChance == null || curChance != wantChance) {
                int delta = wantChance - (curChance == null ? 0 : curChance);
                if (delta != 0) {
                    c.addLootChance(delta);
                }
            }
            Integer curCoins = c.getCoinNum();
            int wantCoins = data.coinNum;
            if (curCoins == null || curCoins != wantCoins) {
                int delta = wantCoins - (curCoins == null ? 0 : curCoins);
                if (delta != 0) {
                    c.addCoinNum(delta);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
