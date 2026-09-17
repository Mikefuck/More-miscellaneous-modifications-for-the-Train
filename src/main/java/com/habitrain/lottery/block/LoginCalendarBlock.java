package com.habitrain.lottery.block;

import com.habitrain.lottery.grant.LoginRewardService;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Black-concrete calendar block. Horizontal FACING = display face normal (player-facing when placed).
 * Back face use: show/sync login streak. Multi-tile same-facing neighbors tile one calendar.
 */
public final class LoginCalendarBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<LoginCalendarBlock> CODEC = simpleCodec(LoginCalendarBlock::new);

    public LoginCalendarBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public LoginCalendarBlock() {
        this(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(1.5f, 6.0f)
                .sound(SoundType.STONE)
                .pushReaction(PushReaction.BLOCK));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Display face toward the placing player (horizontal opposite of look direction).
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** Client mixin indexes place/remove; keep this class free of client-only types. */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        Direction display = state.getValue(FACING);
        Direction hitFace = hit.getDirection();
        // Back face = opposite of display; also allow any face for convenience if crouching? Spec: back activates.
        if (hitFace == display.getOpposite() || hitFace == display) {
            if (player instanceof ServerPlayer sp) {
                LoginRewardService.onInspect(sp);
            }
            return InteractionResult.CONSUME;
        }
        // Sides: still allow inspect so players find the feature
        if (player instanceof ServerPlayer sp) {
            LoginRewardService.onInspect(sp);
        }
        return InteractionResult.CONSUME;
    }
}
