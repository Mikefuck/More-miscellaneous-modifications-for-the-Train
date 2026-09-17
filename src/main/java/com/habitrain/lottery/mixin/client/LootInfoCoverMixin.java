package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.client.PoolCoverLookup;
import net.minecraft.resources.ResourceLocation;
import org.agmas.noellesroles.client.screen.LootInfoScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SRE binds pool sketch art to {@code PoolID} ({@code pool_bg{N}.png}).
 * Our reorder swaps PoolIDs for sidebar sort, so art would stay on the slot.
 * Redirect to the pool's stable {@code CoverID} from the latest config snapshot.
 */
@Mixin(value = LootInfoScreen.class, remap = false)
public class LootInfoCoverMixin {

    @Inject(method = "getPoolSketchTexture", at = @At("HEAD"), cancellable = true)
    private void habi$coverByStableId(int poolID, CallbackInfoReturnable<ResourceLocation> cir) {
        cir.setReturnValue(PoolCoverLookup.sketchTexture(poolID));
    }
}
