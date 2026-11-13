package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes shop balance changes on {@link SREPlayerShopComponent#sync()} while suppressing round-reset {@code init()}.
 */
@Mixin(value = SREPlayerShopComponent.class, remap = false)
public abstract class ShopCoinLedgerMixin {

    @Shadow
    public int balance;

    @Shadow
    public abstract Player getPlayer();

    @Inject(method = "init", at = @At("HEAD"))
    private void habi$beginInitSuppress(CallbackInfo ci) {
        MatchDetailRecorder.beginInitSuppress(this.getPlayer());
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void habi$endInitSuppress(CallbackInfo ci) {
        MatchDetailRecorder.endInitSuppress(this.getPlayer(), this.balance);
    }

    @Inject(method = "sync", at = @At("HEAD"))
    private void habi$observeShopSync(CallbackInfo ci) {
        MatchDetailRecorder.onShopBalanceObserved(this.getPlayer(), this.balance);
    }
}
