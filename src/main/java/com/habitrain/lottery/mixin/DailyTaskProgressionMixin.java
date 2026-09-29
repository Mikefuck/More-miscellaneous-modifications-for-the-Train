package com.habitrain.lottery.mixin;

import com.habitrain.lottery.daily.config.DailyTaskTracker;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.progression.ProgressionDataManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Daily-task observers on upstream progression callbacks. Both inject at HEAD and never cancel:
 * <ul>
 *   <li>{@code onRoundQuestFinished(ServerPlayer, String)} — the single funnel every mood / scene
 *       task completion goes through ({@code RoleMethodDispatcher.callOnFinishQuest}); the
 *       {@code Player} overload only forwards here, so hooking this one counts each task once;</li>
 *   <li>{@code onRoundSettled(ServerPlayer, SRERole, boolean)} — called by
 *       {@code GameUtils.recordWinStats} with the final, authoritative per-player win flag.</li>
 * </ul>
 */
@Mixin(value = ProgressionDataManager.class, remap = false)
public class DailyTaskProgressionMixin {
    @Inject(method = "onRoundQuestFinished(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private static void habi$dailyQuest(ServerPlayer player, String quest, CallbackInfo ci) {
        DailyTaskTracker.onQuestFinished(player, quest);
    }

    @Inject(method = "onRoundSettled(Lnet/minecraft/server/level/ServerPlayer;Lio/wifi/starrailexpress/api/SRERole;Z)V",
            at = @At("HEAD"))
    private static void habi$dailySettled(ServerPlayer player, SRERole role, boolean winner, CallbackInfo ci) {
        DailyTaskTracker.onRoundSettled(player, role, winner);
    }
}
