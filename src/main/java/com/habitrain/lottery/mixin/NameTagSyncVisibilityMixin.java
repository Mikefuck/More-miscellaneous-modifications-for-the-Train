package com.habitrain.lottery.mixin;

import net.exmo.sre.nametag.NameTagInventoryComponent;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Original CCA only syncs nametags to the owning player. Client nameplates call
 * {@code getDisplayName} on remote players, so titles never appear above others' heads.
 * Sync to everyone in tracking range (default CCA visibility).
 */
@Mixin(value = NameTagInventoryComponent.class, remap = false)
public class NameTagSyncVisibilityMixin {

    @Inject(method = "shouldSyncWith", at = @At("HEAD"), cancellable = true)
    private void habi$syncToAll(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
