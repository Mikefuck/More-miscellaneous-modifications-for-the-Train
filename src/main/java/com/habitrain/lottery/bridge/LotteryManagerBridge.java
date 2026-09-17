package com.habitrain.lottery.bridge;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.config.LotteryConfigService;
import com.habitrain.lottery.config.PoolConfigModels;
import com.habitrain.lottery.skin.SkinPoolInjector;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import net.minecraft.server.MinecraftServer;
import org.agmas.noellesroles.utils.lottery.LotteryManager;
import org.agmas.noellesroles.utils.lottery.LotteryPoolsConfig;
import org.agmas.noellesroles.utils.lottery.LotteryPoolsConfigParser;
import org.agmas.noellesroles.utils.lottery.LotteryRecordStorage;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bridges world-authored pools into SRE {@link LotteryManager}.
 * SRE reloads from {@code lottery_skin_data/lottery_pool.json}; we write that
 * shadow from already-validated in-memory pools (never copy the world file).
 */
public final class LotteryManagerBridge {
    private LotteryManagerBridge() {
    }

    public static boolean applyWorldPools(MinecraftServer server) {
        try {
            LotteryConfigService cfg = LotteryConfigService.get();
            PoolConfigModels.Root pools = SkinPoolInjector.withRegisteredSkins(cfg.getPools());
            if (pools == null || pools.Pools == null || pools.Pools.isEmpty()) {
                HabiLotteryMod.LOGGER.warn("No pools config to apply");
                return false;
            }
            String json = LotteryConfigService.GSON.toJson(pools);
            if (!validatePoolsJson(json)) {
                HabiLotteryMod.LOGGER.error("Refusing SRE pool apply: in-memory pools failed validation");
                return false;
            }
            Path sreDir = LotteryRecordStorage.getInstance().getLotteryDataDir();
            Files.createDirectories(sreDir);
            Path srePool = sreDir.resolve("lottery_pool.json");
            if (!AtomicJsonFiles.writeJson(srePool, pools, LotteryConfigService.GSON, true)) {
                HabiLotteryMod.LOGGER.error("Failed writing SRE shadow pool file {}", srePool);
                return false;
            }
            LotteryManager.getInstance().reload();
            HabiLotteryMod.LOGGER.info("Applied {} lottery pools to LotteryManager",
                    LotteryManager.getInstance().getLotteryPools().size());
            return true;
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Failed applying lottery pools", e);
            return false;
        }
    }

    public static void reloadFromDisk(MinecraftServer server) {
        LotteryConfigService.get().loadOrSeed(server);
        if (LotteryConfigService.get().isLoadFailed()) {
            HabiLotteryMod.LOGGER.error("Reload aborted pool apply: config loadFailed");
            return;
        }
        applyWorldPools(server);
    }

    /** Validate JSON by parsing with SRE parser. */
    public static boolean validatePoolsJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            LotteryPoolsConfig cfg = LotteryPoolsConfigParser.parse(json);
            return cfg != null && cfg.getPools() != null && !cfg.getPools().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
