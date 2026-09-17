package com.habitrain.lottery.bridge;

import com.habitrain.lottery.HabiLotteryMod;

/**
 * Ensures StarRailExpress item skin sync flags are properly enabled on server start
 * so that unlocked skins are included in CCA network sync packets (isItemSkinManagementEnabled=true)
 * without enabling remote MySQL sync.
 */
public final class SreSkinConfigBridge {
    private SreSkinConfigBridge() {
    }

    public static void ensureServerFlags() {
        try {
            Class<?> cls = Class.forName("io.wifi.starrailexpress.SREConfig");
            Object config = cls.getMethod("instance").invoke(null);
            cls.getField("isItemSkinEnabled").setBoolean(config, true);
            cls.getField("isItemSkinManagementEnabled").setBoolean(config, true);
            cls.getField("itemSkinSyncServerEnabled").setBoolean(config, false);
            HabiLotteryMod.LOGGER.info("[habitrain_lottery] SRE skin sync flags: enabled=true, management=true");
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Failed ensuring SRE skin sync flags: {}", t.toString());
        }
    }
}
