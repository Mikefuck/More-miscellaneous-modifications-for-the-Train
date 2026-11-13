package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pro.fazeclan.river.stupid_express.role.avaricious.AvariciousGoldHandler;

/**
 * Attributes Avaricious role passive gold ticks.
 */
@Mixin(value = AvariciousGoldHandler.class, remap = false)
public class AvariciousIncomeRecordMixin {

    @Inject(method = "playerServerTick", at = @At("HEAD"))
    private static void habi$pushAvariciousIncome(
            ServerPlayer player,
            SREGameWorldComponent gameWorldComponent,
            CallbackInfo ci
    ) {
        MatchDetailRecorder.pushReason(MatchDetailRecorder.CoinReason.PASSIVE_AVARICIOUS);
    }

    @Inject(method = "playerServerTick", at = @At("RETURN"))
    private static void habi$popAvariciousIncome(
            ServerPlayer player,
            SREGameWorldComponent gameWorldComponent,
            CallbackInfo ci
    ) {
        MatchDetailRecorder.popReason();
    }
}
