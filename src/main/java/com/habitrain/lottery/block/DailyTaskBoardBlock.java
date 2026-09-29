package com.habitrain.lottery.block;

import com.habitrain.lottery.network.LotteryNetwork;
import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Station-style mission kiosk (3D model: plinth, livery pedestal, walnut desk, tilted display);
 * the screen faces the placer. Any right click opens the daily board.
 */
public final class DailyTaskBoardBlock extends Block {
    public static final MapCodec<DailyTaskBoardBlock> CODEC = simpleCodec(DailyTaskBoardBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** Outline boxes authored for a north-facing screen, in model pixels. */
    private static final double[][] NORTH_BOXES = {
            {1, 0, 1, 15, 2, 15},        // plinth + skirting
            {3, 2, 4, 13, 9, 13},        // pedestal
            {1.5, 8.75, 1, 14.5, 10.5, 14.5}, // desk
            {2, 10.5, 7, 14, 16, 13},    // tilted display + canopy
    };
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : NORTH_BOXES) shape = Shapes.or(shape, rotated(b, dir));
            SHAPES.put(dir, shape.optimize());
        }
    }

    public DailyTaskBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public DailyTaskBoardBlock() {
        this(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE)
                .strength(2.0f, 6.0f).sound(SoundType.METAL).lightLevel(state -> 9).noOcclusion());
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer) {
            LotteryNetwork.sendDailyTaskSnapshot(serverPlayer, true);
        }
        return InteractionResult.CONSUME;
    }

    /** Same turn as the blockstate's y rotation: north 0, east 90, south 180, west 270. */
    private static VoxelShape rotated(double[] b, Direction dir) {
        double x1 = b[0], z1 = b[2], x2 = b[3], z2 = b[5];
        return switch (dir) {
            case EAST -> Block.box(16 - z2, b[1], x1, 16 - z1, b[4], x2);
            case SOUTH -> Block.box(16 - x2, b[1], 16 - z2, 16 - x1, b[4], 16 - z1);
            case WEST -> Block.box(z1, b[1], 16 - x2, z2, b[4], 16 - x1);
            default -> Block.box(x1, b[1], z1, x2, b[4], z2);
        };
    }
}
