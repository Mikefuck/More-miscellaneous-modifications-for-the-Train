package com.habitrain.lottery.client;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.config.PoolConfigModels;
import com.habitrain.lottery.skin.SkinPoolInjector;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.agmas.noellesroles.client.screen.LootInfoScreen;
import org.agmas.noellesroles.utils.Pair;
import org.agmas.noellesroles.utils.lottery.LotteryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes world-authored pool config into the SRE client-side {@link LotteryManager}
 * and refreshes an already-open loot UI. SRE only fills missing pool IDs over the
 * network and never removes disabled pools, so admin changes from Mod Menu would
 * otherwise stay invisible on the gacha page.
 */
@Environment(EnvType.CLIENT)
public final class SreClientPoolSync {
    private SreClientPoolSync() {
    }

    public static void applyFromConfig(PoolConfigModels.Root root) {
        try {
            root = SkinPoolInjector.withRegisteredSkins(root);
            // Cover art must track pool content, not the post-sort PoolID slot.
            PoolCoverLookup.rebuild(root);
            LotteryManager manager = LotteryManager.getInstance();
            manager.clearPools();
            if (root == null || root.Pools == null) {
                refreshLootUiIfOpen();
                return;
            }
            int loaded = 0;
            for (PoolConfigModels.Pool pool : root.Pools) {
                if (pool == null || !pool.Enable) {
                    continue;
                }
                if (pool.PoolName == null || pool.PoolName.isBlank()) {
                    continue;
                }
                if (pool.PoolType == null || pool.PoolType.isBlank()) {
                    continue;
                }
                if (pool.QualityListGroup == null || pool.QualityListGroup.isEmpty()) {
                    continue;
                }
                List<Pair<Double, List<String>>> qualities = new ArrayList<>();
                double sum = 0.0;
                boolean valid = true;
                for (PoolConfigModels.QualityBand band : pool.QualityListGroup) {
                    if (band == null || band.Probability == null || band.Probability <= 0
                            || band.ItemList == null || band.ItemList.isEmpty()) {
                        valid = false;
                        break;
                    }
                    sum += band.Probability;
                    qualities.add(new Pair<>(band.Probability, new ArrayList<>(band.ItemList)));
                }
                if (!valid || sum < 0.999 || sum > 1.001 || qualities.isEmpty()) {
                    HabiLotteryMod.LOGGER.warn("Skip invalid client pool sync id={} name={}", pool.PoolID, pool.PoolName);
                    continue;
                }
                manager.addLotteryPool(new LotteryManager.LotteryPool(
                        pool.PoolName, pool.PoolID, pool.PoolType, qualities));
                loaded++;
            }
            manager.sortPools();
            HabiLotteryMod.LOGGER.info("Synced {} enabled pools into client LotteryManager", loaded);
            refreshLootUiIfOpen();
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Failed syncing pools to client LotteryManager: {}", t.toString());
        }
    }

    public static void refreshLootUiIfOpen() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            Screen screen = client.screen;
            if (!(screen instanceof LootInfoScreen)) {
                return;
            }
            // Rebuild sidebar buttons from the updated LotteryManager list, keeping the
            // last known coin/draw values synced by OpenLootUiS2C.
            client.setScreen(new LootInfoScreen(0,
                    com.habitrain.lottery.network.LotteryNetwork.ClientLotteryState.lastCoinNumber,
                    com.habitrain.lottery.network.LotteryNetwork.ClientLotteryState.lastLotteryChance,
                    null));
        });
    }
}
