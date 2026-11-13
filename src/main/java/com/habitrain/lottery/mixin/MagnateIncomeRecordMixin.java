package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pro.fazeclan.river.stupid_express.modifier.magnate.MagnatePassiveIncomeHandler;

/**
 * Attributes passive coin income ticks from {@link MagnatePassiveIncomeHandler}.
 */
@Mixin(value = MagnatePassiveIncomeHandler.class, remap = false)
public class MagnateIncomeRecordMixin {

    @Inject(method = "onServerTick", at = @At("HEAD"))
    private static void habi$pushMagnateIncome(MinecraftServer server, CallbackInfo ci) {
        MatchDetailRecorder.pushReason(MatchDetailRecorder.CoinReason.PASSIVE_MAGNATE);
    }

    @Inject(method = "onServerTick", at = @At("RETURN"))
    private static void habi$popMagnateIncome(MinecraftServer server, CallbackInfo ci) {
        MatchDetailRecorder.popReason();
    }
}
