package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.noellesroles.role.ModifierEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes coin grants triggered by {@link ModifierEffects}.
 */
@Mixin(value = ModifierEffects.class, remap = false)
public class ModifierEffectsRecordMixin {

    @Inject(method = "onServerTick", at = @At("HEAD"))
    private static void habi$pushModifierTick(MinecraftServer server, CallbackInfo ci) {
        MatchDetailRecorder.pushReason(MatchDetailRecorder.CoinReason.MODIFIER_EFFECT);
    }

    @Inject(method = "onServerTick", at = @At("RETURN"))
    private static void habi$popModifierTick(MinecraftServer server, CallbackInfo ci) {
        MatchDetailRecorder.popReason();
    }

    @Inject(method = "onBigEaterTaskComplete", at = @At("HEAD"))
    private static void habi$pushBigEater(ServerPlayer player, CallbackInfo ci) {
        MatchDetailRecorder.pushReason(MatchDetailRecorder.CoinReason.BIG_EATER);
    }

    @Inject(method = "onBigEaterTaskComplete", at = @At("RETURN"))
    private static void habi$popBigEater(ServerPlayer player, CallbackInfo ci) {
        MatchDetailRecorder.popReason();
    }

    @Inject(method = "onStingyPurchase", at = @At("HEAD"))
    private static void habi$pushStingyRefund(ServerPlayer player, int originalPrice, CallbackInfo ci) {
        MatchDetailRecorder.pushReason(MatchDetailRecorder.CoinReason.STINGY_REFUND);
    }

    @Inject(method = "onStingyPurchase", at = @At("RETURN"))
    private static void habi$popStingyRefund(ServerPlayer player, int originalPrice, CallbackInfo ci) {
        MatchDetailRecorder.popReason();
    }
}
