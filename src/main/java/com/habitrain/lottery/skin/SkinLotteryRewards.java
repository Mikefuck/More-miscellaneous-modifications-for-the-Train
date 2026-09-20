package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.grant.LotteryGrantService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;

/** Local reward resolution; the adapter supplies pool weights and result indices. */
public final class SkinLotteryRewards {
    private static final double[] COIN_RATIOS = {0.1, 0.125, 0.25, 0.5, 1, 2, 3};
    private SkinLotteryRewards() {}
    public static boolean isCoin(String entry) { return "coin".equals(entry); }
    public static int chooseBand(List<Double> weights, double random) {
        double cumulative = 0;
        for (int i = 0; i < weights.size(); i++) {
            Double weight = weights.get(i);
            if (weight == null || !Double.isFinite(weight) || weight <= 0) continue;
            cumulative += weight;
            if (random < cumulative) return i;
        }
        return -1;
    }
    public static void award(ServerPlayer player, String entry, int quality) {
        var store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive() || store.isLoadFailed(player.getUUID())) throw new IllegalStateException("Skin store unavailable");
        double base = 648 * COIN_RATIOS[Math.max(0, Math.min(quality, COIN_RATIOS.length - 1))];
        if (isCoin(entry)) { store.addCoinNum(player.getUUID(), (int) (base * 1.1)); return; }
        var skin = HabiSkinApi.fromEntry(entry).orElseThrow(() -> new IllegalArgumentException("Unknown skin reward: " + entry));
        if (store.isSkinUnlocked(player.getUUID(), skin.type(), skin.id())) store.addCoinNum(player.getUUID(), LotteryGrantService.applyDuplicateCoin((int) base));
        else store.unlockSkin(player.getUUID(), skin.type(), skin.id());
    }
}
