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
 * Mailbox block: right-click opens the in-game mailbox UI
 * ({@code MailboxScreen}) via the server-synced {@code OpenMailboxS2C} path.
 */
public final class MailboxBlock extends Block {
    public static final MapCodec<MailboxBlock> CODEC = simpleCodec(MailboxBlock::new);

    public MailboxBlock(Properties properties) {
        super(properties);
    }

    public MailboxBlock() {
        this(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BROWN)
                .strength(2.0f, 6.0f)
                .sound(SoundType.METAL)
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
            LotteryNetwork.sendOpenMailbox(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }
}
