package com.habitrain.lottery.mixin;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.exmo.sre.nametag.NameTagInventoryComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Ignore star-rail MySQL / network nametag sync. Titles live only in
 * world {@code habitrain_lottery/titles/}.
 */
@Mixin(value = NameTagInventoryComponent.class, remap = false)
public class NameTagMysqlBypassMixin {

    private static boolean takeover() {
        return PlayerLotteryStore.get().isTakeoverActive();
    }

    @Inject(method = "initializeNetworkSync", at = @At("HEAD"), cancellable = true)
    private void habi$disableInit(String host, int port, String key, CallbackInfo ci) {
        if (!takeover()) {
            return;
        }
        try {
            ((NameTagInventoryComponent) (Object) this).disableNetworkSync();
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("nametag disableNetworkSync failed: {}", t.toString());
        }
        ci.cancel();
    }

    @Inject(method = "syncFromLinkedServer", at = @At("HEAD"), cancellable = true)
    private void habi$noPull(CallbackInfo ci) {
        if (takeover()) {
            ci.cancel();
        }
    }

    @Inject(method = "syncToNetwork", at = @At("HEAD"), cancellable = true)
    private void habi$noPush(CallbackInfo ci) {
        if (takeover()) {
            ci.cancel();
        }
    }

    @Inject(method = "flushNetworkSyncBlocking", at = @At("HEAD"), cancellable = true)
    private void habi$noFlush(CallbackInfoReturnable<Boolean> cir) {
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

    @Inject(method = "applyNetworkNametagData", at = @At("HEAD"), cancellable = true)
    private void habi$noApplyNetwork(Map<String, Object> data, CallbackInfo ci) {
        if (takeover()) {
            ci.cancel();
        }
    }
}
