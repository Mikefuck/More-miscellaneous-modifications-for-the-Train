package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import com.habitrain.lottery.record.MatchReplayEventFormatter;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.wifi.starrailexpress.api.replay.GameReplayData;
import io.wifi.starrailexpress.api.replay.GameReplayManager;
import io.wifi.starrailexpress.api.replay.ReplayEvent;
import io.wifi.starrailexpress.api.replay.ReplayTimelineEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hooks {@link GameReplayManager} lifecycle, store buy attribution, and timeline text formatting.
 */
@Mixin(value = GameReplayManager.class, remap = false)
public class GameReplayManagerDetailMixin {

    @Inject(method = "resetReplay", at = @At("HEAD"))
    private void habi$resetDetailRecorder(CallbackInfo ci) {
        MatchDetailRecorder.reset();
    }

    @Inject(method = "recordStoreBuy(Ljava/util/UUID;Lnet/minecraft/resources/ResourceLocation;IIZ)V", at = @At("HEAD"))
    private void habi$recordStoreBuySpend(
            UUID playerUuid,
            ResourceLocation itemBought,
            int amount,
            int price,
            boolean hidden,
            CallbackInfo ci
    ) {
        MatchDetailRecorder.onStoreBuyRecorded(playerUuid, price);
    }

    @WrapMethod(method = "buildTimelineEvent")
    private ReplayTimelineEvent habi$fillMissingTimelineText(
            GameReplayData.ReplayEvent dataEvent,
            ReplayEvent replayEvent,
            Component eventText,
            Operation<ReplayTimelineEvent> original
    ) {
        Component resolved = MatchReplayEventFormatter.resolveTimelineText(
                (GameReplayManager) (Object) this,
                dataEvent,
                replayEvent,
                eventText
        );
        return original.call(dataEvent, replayEvent, resolved);
    }
}
