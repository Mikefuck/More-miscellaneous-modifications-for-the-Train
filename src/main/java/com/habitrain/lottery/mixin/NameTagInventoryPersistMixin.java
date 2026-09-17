package com.habitrain.lottery.mixin;

import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.TitleService;
import net.exmo.sre.nametag.NameTagInventoryComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After any CCA nametag mutation, snapshot owned/current into local JSON.
 */
@Mixin(value = NameTagInventoryComponent.class, remap = false)
public class NameTagInventoryPersistMixin {

    @Inject(method = "addNameTag", at = @At("RETURN"))
    private void habi$persistAdd(String tag, CallbackInfo ci) {
        habi$persist();
    }

    @Inject(method = "removeNameTag", at = @At("RETURN"))
    private void habi$persistRemove(String tag, CallbackInfo ci) {
        habi$persist();
    }

    @Inject(method = "setCurrentNameTag", at = @At("RETURN"))
    private void habi$setCurrent(String tag, CallbackInfo ci) {
        habi$persist();
    }

    @Inject(method = "clear", at = @At("RETURN"))
    private void habi$persistClear(CallbackInfo ci) {
        habi$persist();
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void habi$persistInit(CallbackInfo ci) {
        habi$persist();
    }

    private void habi$persist() {
        if (TitleService.isApplyingLocal() || TitleService.isForceSyncing()) {
            return;
        }
        NameTagInventoryComponent self = (NameTagInventoryComponent) (Object) this;
        Player p = self.getPlayer();
        if (!(p instanceof ServerPlayer sp) || !WorldLotteryPaths.ready()) {
            return;
        }
        TitleService.persistFromComponent(sp);
        // Skin-menu /nametag command path: push CCA + display-name refresh immediately
        TitleService.forceResync(sp);
    }
}
