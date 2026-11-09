package com.habitrain.lottery.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCalendarGroupCoplanarTest {

    @BeforeEach
    void resetCache() {
        LoginCalendarGroup.invalidateCache();
    }

    private static Set<BlockPos> buildWall(BlockPos bottomLeft, Direction facing, int width, int height) {
        Direction right = LoginCalendarGroup.rightOf(facing);
        Set<BlockPos> wall = new HashSet<>();
        for (int u = 0; u < width; u++) {
            for (int v = 0; v < height; v++) {
                wall.add(bottomLeft.relative(right, u).above(v));
            }
        }
        return wall;
    }

    @Test
    void parallelWallsAlongFacingOrOppositeNeverMergeAcrossAllHorizontalFacings() {
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            LoginCalendarGroup.invalidateCache();

            BlockPos frontAnchor = new BlockPos(10, 64, 10);
            BlockPos backAnchor = frontAnchor.relative(facing.getOpposite());
            BlockPos aheadAnchor = frontAnchor.relative(facing);

            Set<BlockPos> frontWall = buildWall(frontAnchor, facing, 7, 4);
            Set<BlockPos> backWall = buildWall(backAnchor, facing, 7, 4);
            Set<BlockPos> aheadWall = buildWall(aheadAnchor, facing, 7, 4);

            Set<BlockPos> allTiles = new HashSet<>();
            allTiles.addAll(frontWall);
            allTiles.addAll(backWall);
            allTiles.addAll(aheadWall);
            assertEquals(84, allTiles.size());

            List<BlockPos> floodedFront = LoginCalendarGroup.flood(frontAnchor, facing, allTiles::contains);
            assertEquals(28, floodedFront.size(), "Flood leaked across depth for facing=" + facing);
            assertTrue(frontWall.containsAll(floodedFront));
            for (BlockPos rear : backWall) {
                assertFalse(floodedFront.contains(rear));
            }
            for (BlockPos ahead : aheadWall) {
                assertFalse(floodedFront.contains(ahead));
            }

            LoginCalendarGroup frontGroup = LoginCalendarGroup.resolve(frontAnchor, facing, allTiles::contains);
            assertEquals(7, frontGroup.sizeU);
            assertEquals(4, frontGroup.sizeV);
            assertEquals(0, frontGroup.localU);
            assertEquals(3, frontGroup.localV);

            // Rear wall was never merged into the front wall's cache entry and resolves independently to 7x4.
            LoginCalendarGroup backGroup = LoginCalendarGroup.resolve(backAnchor, facing, allTiles::contains);
            assertEquals(7, backGroup.sizeU);
            assertEquals(4, backGroup.sizeV);
            assertEquals(0, backGroup.localU);
            assertEquals(3, backGroup.localV);
        }
    }

    @Test
    void singleDepthNeighborDirectlyBehindOrInFrontDoesNotJoin1x1Tile() {
        BlockPos origin = new BlockPos(0, 70, 0);
        Set<BlockPos> depthLine = Set.of(
                origin,
                origin.north(),
                origin.south()
        );

        LoginCalendarGroup group = LoginCalendarGroup.resolve(origin, Direction.SOUTH, depthLine::contains);
        assertEquals(1, group.sizeU);
        assertEquals(1, group.sizeV);
        assertEquals(0, group.localU);
        assertEquals(0, group.localV);
    }
}
