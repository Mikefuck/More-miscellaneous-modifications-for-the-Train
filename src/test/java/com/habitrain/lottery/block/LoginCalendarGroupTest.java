package com.habitrain.lottery.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCalendarGroupTest {

    @BeforeEach
    void resetCache() {
        LoginCalendarGroup.invalidateCache();
    }

    @Test
    void rightOfMatchesDisplayFace() {
        assertEquals(Direction.WEST, LoginCalendarGroup.rightOf(Direction.NORTH));
        assertEquals(Direction.EAST, LoginCalendarGroup.rightOf(Direction.SOUTH));
        assertEquals(Direction.SOUTH, LoginCalendarGroup.rightOf(Direction.WEST));
        assertEquals(Direction.NORTH, LoginCalendarGroup.rightOf(Direction.EAST));
    }

    @Test
    void projectUAlongFacingRight() {
        BlockPos origin = new BlockPos(10, 64, 20);
        assertEquals(3, LoginCalendarGroup.projectU(new BlockPos(13, 64, 20), origin, Direction.EAST));
        assertEquals(-3, LoginCalendarGroup.projectU(new BlockPos(13, 64, 20), origin, Direction.WEST));
        assertEquals(4, LoginCalendarGroup.projectU(new BlockPos(10, 64, 24), origin, Direction.SOUTH));
    }

    @Test
    void layoutWallAssignsLocalUvFor7x4() {
        List<BlockPos> members = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 10; y < 14; y++) {
                members.add(new BlockPos(x, y, 5));
            }
        }
        Map<Long, LoginCalendarGroup> wall = LoginCalendarGroup.layoutWall(members, Direction.SOUTH);
        assertEquals(28, wall.size());

        LoginCalendarGroup topLeft = wall.get(new BlockPos(0, 13, 5).asLong());
        LoginCalendarGroup bottomRight = wall.get(new BlockPos(6, 10, 5).asLong());
        assertEquals(7, topLeft.sizeU);
        assertEquals(4, topLeft.sizeV);
        assertEquals(0, topLeft.localU);
        assertEquals(0, topLeft.localV);
        assertEquals(6, bottomRight.localU);
        assertEquals(3, bottomRight.localV);
        assertEquals(Direction.SOUTH, topLeft.facing);
    }

    @Test
    void secondResolveOfConnectedWallDoesNotReflood() {
        Set<BlockPos> tiles = new HashSet<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 4; y++) {
                tiles.add(new BlockPos(x, y, 0));
            }
        }
        AtomicInteger probes = new AtomicInteger();
        LoginCalendarGroup.SameFacingPredicate pred = pos -> {
            probes.incrementAndGet();
            return tiles.contains(pos);
        };

        BlockPos origin = new BlockPos(0, 3, 0);
        LoginCalendarGroup first = LoginCalendarGroup.resolve(origin, Direction.SOUTH, pred);
        int afterFlood = probes.get();
        assertTrue(afterFlood > 0);
        assertEquals(7, first.sizeU);
        assertEquals(4, first.sizeV);
        assertEquals(0, first.localU);
        assertEquals(0, first.localV);

        LoginCalendarGroup second = LoginCalendarGroup.resolve(origin, Direction.SOUTH, pred);
        assertEquals(afterFlood, probes.get());
        assertEquals(first.sizeU, second.sizeU);
        assertEquals(first.localU, second.localU);
        assertEquals(first.localV, second.localV);

        LoginCalendarGroup other = LoginCalendarGroup.resolve(new BlockPos(6, 0, 0), Direction.SOUTH, pred);
        assertEquals(afterFlood, probes.get());
        assertEquals(7, other.sizeU);
        assertEquals(4, other.sizeV);
        assertEquals(6, other.localU);
        assertEquals(3, other.localV);
    }

    @Test
    void floodDedupesNeighborsViaSeen() {
        Set<BlockPos> tiles = Set.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(0, 1, 0),
                new BlockPos(1, 1, 0)
        );
        List<BlockPos> members = LoginCalendarGroup.flood(new BlockPos(0, 0, 0), tiles::contains);
        assertEquals(4, members.size());
        assertEquals(4, new HashSet<>(members).size());
    }

    @Test
    void invalidateAllowsReflood() {
        Set<BlockPos> tiles = Set.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        AtomicInteger probes = new AtomicInteger();
        LoginCalendarGroup.SameFacingPredicate pred = pos -> {
            probes.incrementAndGet();
            return tiles.contains(pos);
        };
        LoginCalendarGroup.resolve(new BlockPos(0, 0, 0), Direction.NORTH, pred);
        int afterFirst = probes.get();
        LoginCalendarGroup.invalidateCache();
        LoginCalendarGroup.resolve(new BlockPos(0, 0, 0), Direction.NORTH, pred);
        assertTrue(probes.get() > afterFirst);
    }

    @Test
    void cacheHitReturnsSameLogicalLayout() {
        Set<BlockPos> tiles = Set.of(new BlockPos(2, 5, 2), new BlockPos(3, 5, 2));
        LoginCalendarGroup.SameFacingPredicate pred = tiles::contains;
        LoginCalendarGroup a = LoginCalendarGroup.resolve(new BlockPos(2, 5, 2), Direction.EAST, pred);
        LoginCalendarGroup b = LoginCalendarGroup.resolve(new BlockPos(2, 5, 2), Direction.EAST, pred);
        assertEquals(a.sizeU, b.sizeU);
        assertEquals(a.sizeV, b.sizeV);
        assertEquals(a.localU, b.localU);
        assertEquals(a.localV, b.localV);
        assertEquals(a.facing, b.facing);
        assertSame(a, b);
    }
}
