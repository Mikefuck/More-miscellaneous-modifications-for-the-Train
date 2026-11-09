package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.block.LoginCalendarBlock;
import com.habitrain.lottery.client.render.LoginCalendarIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Maintains {@link LoginCalendarIndex} whenever a block state changes in a client chunk
 * (vanilla {@code BlockBehaviour#onPlace} is never invoked on the client by {@code LevelChunk#setBlockState}).
 */
@Mixin(LevelChunk.class)
public class LoginCalendarBlockClientMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void habi$indexOnClientBlockStateChange(
            BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir
    ) {
        BlockState previous = cir.getReturnValue();
        if (previous == null) {
            return;
        }
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (level == null || !level.isClientSide) {
            return;
        }
        boolean wasCalendar = previous.getBlock() instanceof LoginCalendarBlock;
        boolean isCalendar = state.getBlock() instanceof LoginCalendarBlock;
        if (!wasCalendar && !isCalendar) {
            return;
        }
        LoginCalendarIndex.syncLevel(level);
        if (isCalendar) {
            LoginCalendarIndex.add(pos);
        } else {
            LoginCalendarIndex.remove(pos);
        }
    }
}
