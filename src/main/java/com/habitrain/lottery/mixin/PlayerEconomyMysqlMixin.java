package com.habitrain.lottery.mixin;

import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.data.PlayerEconomyManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents upstream {@link PlayerEconomyManager} from pulling legacy MySQL economy /
 * skin records while world JSON takeover is active ({@code I-16}).
 */
@Mixin(value = PlayerEconomyManager.class, remap = false)
public abstract class PlayerEconomyMysqlMixin {

    @Inject(method = "isDatabaseEnabled()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void habi$disableEconomyMysql(CallbackInfoReturnable<Boolean> cir) {
        if (PlayerLotteryStore.get().isTakeoverActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "reloadFromDatabase(Lnet/minecraft/server/level/ServerPlayer;Lio/wifi/starrailexpress/data/PlayerEconomyManager$Entry;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void habi$skipReloadFromDatabase(
            ServerPlayer player,
            @Coerce Object entry,
            CallbackInfo ci) {
        if (PlayerLotteryStore.get().isTakeoverActive()) {
            ci.cancel();
        }
    }
}
