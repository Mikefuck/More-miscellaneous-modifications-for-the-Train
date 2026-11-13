package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import io.wifi.starrailexpress.api.RoleMethodDispatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks quest completion attribution and flushes/freezes balances before {@code onEndGame} resets shop components.
 */
@Mixin(value = RoleMethodDispatcher.class, remap = false)
public class TaskCoinRecordMixin {

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("HEAD")
    )
    private static void habi$beginQuestScope(
            Player player,
            String quest,
            int taskStreak,
            boolean isParallelTask,
            CallbackInfo ci
    ) {
        MatchDetailRecorder.beginTaskScope(player, quest);
    }

    @Inject(
            method = "callOnFinishQuest(Lnet/minecraft/world/entity/player/Player;Ljava/lang/String;IZ)V",
            at = @At("RETURN")
    )
    private static void habi$endQuestScope(
            Player player,
            String quest,
            int taskStreak,
            boolean isParallelTask,
            CallbackInfo ci
    ) {
        MatchDetailRecorder.endTaskScope(player, quest);
    }

    @Inject(
            method = "onEndGame(Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At("HEAD")
    )
    private static void habi$flushBeforeEndGameReset(ServerLevel serverLevel, CallbackInfo ci) {
        MatchDetailRecorder.flushAndFreezeOnEndGame(serverLevel);
    }
}
