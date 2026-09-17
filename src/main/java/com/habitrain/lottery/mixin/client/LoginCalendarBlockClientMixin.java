package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.block.LoginCalendarBlock;
import com.habitrain.lottery.block.LoginCalendarGroup;
import com.habitrain.lottery.client.render.LoginCalendarIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoginCalendarBlock.class)
public class LoginCalendarBlockClientMixin {

    @Inject(method = "onPlace", at = @At("TAIL"))
    private void habi$indexOnPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
        if (level.isClientSide) {
            LoginCalendarIndex.add(pos);
        }
    }

    @Inject(method = "onRemove", at = @At("HEAD"))
    private void habi$indexOnRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston, CallbackInfo ci) {
        if (!level.isClientSide) {
            return;
        }
        if (newState.getBlock() instanceof LoginCalendarBlock) {
            LoginCalendarGroup.invalidateCache();
            return;
        }
        LoginCalendarIndex.remove(pos);
    }
}
