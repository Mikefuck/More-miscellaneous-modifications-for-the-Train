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
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

/**
 * World terminal that opens the SRE skin gacha UI ({@code LootInfoScreen}).
 */
public final class GachaTerminalBlock extends Block {
    public static final MapCodec<GachaTerminalBlock> CODEC = simpleCodec(GachaTerminalBlock::new);

    public GachaTerminalBlock(Properties properties) {
        super(properties);
    }

    public GachaTerminalBlock() {
        this(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(1.2f, 6.0f)
                .sound(SoundType.METAL)
                .lightLevel(state -> 8)
                .pushReaction(PushReaction.BLOCK));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            LotteryNetwork.sendOpenLootUi(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }
}
