package com.habitrain.lottery.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Same-facing connected calendar tiles form a group; each tile knows its local U/V in the AABB.
 * U = right along display face, V = up (world +Y). localV=0 is top row.
 */
public final class LoginCalendarGroup {
    public static final long CACHE_TTL_MS = 1000L;

    public final int sizeU;
    public final int sizeV;
    public final int localU;
    public final int localV;
    public final Direction facing;

    private static final Map<Long, LoginCalendarGroup> CACHE = new HashMap<>();
    private static long cacheExpireAtMs;

    @FunctionalInterface
    public interface SameFacingPredicate {
        boolean test(BlockPos pos);
    }

    public LoginCalendarGroup(int sizeU, int sizeV, int localU, int localV, Direction facing) {
        this.sizeU = Math.max(1, sizeU);
        this.sizeV = Math.max(1, sizeV);
        this.localU = localU;
        this.localV = localV;
        this.facing = facing;
    }

    public static void invalidateCache() {
        CACHE.clear();
        cacheExpireAtMs = 0L;
    }

    public static LoginCalendarGroup resolve(BlockGetter level, BlockPos origin, BlockState originState) {
        if (!(originState.getBlock() instanceof LoginCalendarBlock)) {
            return new LoginCalendarGroup(1, 1, 0, 0, Direction.NORTH);
        }
        Direction facing = originState.getValue(LoginCalendarBlock.FACING);
        return resolve(origin, facing, pos -> {
            BlockState st = level.getBlockState(pos);
            return st.getBlock() instanceof LoginCalendarBlock
                    && st.getValue(LoginCalendarBlock.FACING) == facing;
        });
    }

    /**
     * BFS the connected wall once, then every member reuses the cached group until TTL or invalidate.
     */
    public static LoginCalendarGroup resolve(BlockPos origin, Direction facing, SameFacingPredicate sameFacing) {
        long now = System.currentTimeMillis();
        long key = origin.asLong();
        if (now < cacheExpireAtMs) {
            LoginCalendarGroup hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        } else if (!CACHE.isEmpty()) {
            CACHE.clear();
        }
        List<BlockPos> members = flood(origin, sameFacing);
        Map<Long, LoginCalendarGroup> wall = layoutWall(members, facing);
        CACHE.putAll(wall);
        cacheExpireAtMs = now + CACHE_TTL_MS;
        LoginCalendarGroup result = wall.get(key);
        return result != null ? result : new LoginCalendarGroup(1, 1, 0, 0, facing);
    }

    public static Map<Long, LoginCalendarGroup> layoutWall(List<BlockPos> members, Direction facing) {
        Map<Long, LoginCalendarGroup> out = new HashMap<>();
        if (members.isEmpty()) {
            return out;
        }
        Direction right = rightOf(facing);
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        BlockPos anchor = members.get(0);
        for (BlockPos p : members) {
            int u = projectU(p, anchor, right);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        int sizeU = maxU - minU + 1;
        int sizeV = maxY - minY + 1;
        for (BlockPos p : members) {
            int localU = projectU(p, anchor, right) - minU;
            int localVFromBottom = p.getY() - minY;
            int localV = (sizeV - 1) - localVFromBottom;
            out.put(p.asLong(), new LoginCalendarGroup(sizeU, sizeV, localU, localV, facing));
        }
        return out;
    }

    static List<BlockPos> flood(BlockPos origin, SameFacingPredicate sameFacing) {
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        List<BlockPos> members = new ArrayList<>();
        q.add(origin.immutable());
        seen.add(origin.asLong());
        while (!q.isEmpty()) {
            BlockPos p = q.removeFirst();
            members.add(p);
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (!seen.add(n.asLong())) {
                    continue;
                }
                if (sameFacing.test(n)) {
                    q.add(n.immutable());
                }
            }
        }
        return members;
    }

    public static Direction rightOf(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    public static int projectU(BlockPos p, BlockPos origin, Direction right) {
        return (p.getX() - origin.getX()) * right.getStepX()
                + (p.getZ() - origin.getZ()) * right.getStepZ();
    }
}
