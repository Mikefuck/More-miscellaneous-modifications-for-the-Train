package com.habitrain.lottery.mixin;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.record.MatchDetailRecorder;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.SREConfig;
import io.wifi.starrailexpress.api.replay.GameReplayData;
import io.wifi.starrailexpress.api.replay.GameReplayManager;
import net.exmo.sre.record.MatchRecord;
import net.exmo.sre.record.MatchRecordService;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When world lottery paths are ready, treat stats sync as enabled so finished matches are recorded,
 * and enrich built {@link MatchRecord} instances with detailed economy/task/survival data.
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

    @Inject(method = "recordFinishedMatch", at = @At("HEAD"))
    private static void habi$flushBeforeRecord(ServerLevel level, CallbackInfo ci) {
        try {
            MatchDetailRecorder.beforeBuildMatchRecord(level);
        } catch (RuntimeException e) {
            HabiLotteryMod.LOGGER.warn("Could not finalize match details; saving the base record", e);
        }
    }

    @Inject(method = "build", at = @At("RETURN"))
    private static void habi$enrichRecord(
            GameReplayManager manager,
            GameReplayData data,
            RegistryAccess registryAccess,
            CallbackInfoReturnable<MatchRecord> cir
    ) {
        try {
            MatchDetailRecorder.enrichBuiltMatchRecord(data, cir.getReturnValue(), registryAccess);
        } catch (RuntimeException e) {
            HabiLotteryMod.LOGGER.warn("Could not append match details; saving the base record", e);
        }
    }

}
