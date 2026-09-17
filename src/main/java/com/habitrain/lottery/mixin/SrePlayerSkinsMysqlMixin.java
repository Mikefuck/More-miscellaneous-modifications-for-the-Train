package com.habitrain.lottery.mixin;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.cca.SREPlayerSkinsComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ignore star-rail MySQL / network skin sync. Skin durability lives only in
 * world {@code habitrain_lottery/players/*.json}.
 */
@Mixin(value = SREPlayerSkinsComponent.class, remap = false)
public class SrePlayerSkinsMysqlMixin {

    private static boolean takeover() {
        return PlayerLotteryStore.get().isTakeoverActive();
    }

    @Inject(method = "initializeNetworkSync", at = @At("HEAD"), cancellable = true)
    private void habi$disableInit(String host, int port, String key, CallbackInfo ci) {
        if (!takeover()) {
            return;
        }
        try {
            ((SREPlayerSkinsComponent) (Object) this).disableNetworkSync();
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("disableNetworkSync failed: {}", t.toString());
        }
        ci.cancel();
    }

    @Inject(method = "pullSkinsFromNetwork", at = @At("HEAD"), cancellable = true)
    private void habi$noPull(CallbackInfo ci) {
        if (takeover()) {
            ci.cancel();
        }
    }

    @Inject(method = "syncSkinsToNetwork", at = @At("HEAD"), cancellable = true)
    private void habi$noPush(CallbackInfo ci) {
        if (takeover()) {
            ci.cancel();
        }
    }

    @Inject(method = "flushSkinDataToDatabase", at = @At("HEAD"), cancellable = true)
    private void habi$noFlush(boolean blocking, CallbackInfoReturnable<Boolean> cir) {
        if (takeover()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "flushNetworkSyncAsyncOnDisconnect", at = @At("HEAD"), cancellable = true)
    private void habi$noFlushDisconnect(CallbackInfo ci) {
        if (takeover()) {
            ci.cancel();
        }
    }

    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private void habi$skipDbTick(CallbackInfo ci) {
        if (takeover()) {
            ci.cancel();
        }
    }
}
