package com.habitrain.lottery.block;

import com.habitrain.lottery.network.LotteryNetwork;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/** Station-style mission terminal; any right click opens the daily board. */
public final class DailyTaskBoardBlock extends Block {
    public static final MapCodec<DailyTaskBoardBlock> CODEC = simpleCodec(DailyTaskBoardBlock::new);

    public DailyTaskBoardBlock(Properties properties) { super(properties); }

    public DailyTaskBoardBlock() {
        this(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE)
                .strength(2.0f, 6.0f).sound(SoundType.METAL).lightLevel(state -> 9));
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer) {
            LotteryNetwork.sendDailyTaskSnapshot(serverPlayer, true);
        }
        return InteractionResult.CONSUME;
    }
}
