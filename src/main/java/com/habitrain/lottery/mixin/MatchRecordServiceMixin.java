package com.habitrain.lottery.mixin;

import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.SREConfig;
import net.exmo.sre.record.MatchRecordService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * When world lottery paths are ready, treat stats sync as enabled so finished matches are recorded.
 */
@Mixin(value = MatchRecordService.class, remap = false)
public class MatchRecordServiceMixin {

    @Redirect(
            method = "recordFinishedMatch",
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/SREConfig;isStatsSyncEnabled:Z"
            )
    )
    private static boolean habi$forceStatsWhenLocal(SREConfig config) {
        if (WorldLotteryPaths.ready()) {
            return true;
        }
        return config != null && config.isStatsSyncEnabled;
    }
}
