package com.habitrain.lottery.storage;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.bridge.EconomyMirror;
import net.minecraft.server.level.ServerPlayer;

/**
 * Legacy SRE import helpers.
 * Automatic import is disabled: world {@code habitrain_lottery/players} is the only durable store.
 */
public final class MigrationService {
    private MigrationService() {
    }

    /**
     * No-op auto import. Marks the flag so older call sites never re-import from SRE/MySQL.
     */
    public static void migrateFromSreIfNeeded(ServerPlayer player, PlayerLotteryData data) {
        if (data == null) {
            return;
        }
        data.migratedFromSre = true;
        HabiLotteryMod.LOGGER.debug(
                "SRE auto-migrate skipped for {} (world JSON is authoritative)",
                player != null ? player.getGameProfile().getName() : "?");
    }

    /**
     * Explicit OP command path: wipe world player lottery fields and re-push empty state to SRE.
     * Does not pull from MySQL/SRE.
     */
    public static void forceMigrate(ServerPlayer player) {
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(player);
        data.migratedFromSre = true;
        data.lootChance = 0;
        data.coinNum = 0;
        data.unlocked.clear();
        data.equipped.clear();
        data.updatedAt = System.currentTimeMillis();
        PlayerLotteryStore.get().markDirty(player.getUUID());
        PlayerLotteryStore.get().flush(player.getUUID());
        EconomyMirror.pushToSre(player, data);
        HabiLotteryMod.LOGGER.info("Force-cleared world lottery data for {}",
                player.getGameProfile().getName());
    }
}
