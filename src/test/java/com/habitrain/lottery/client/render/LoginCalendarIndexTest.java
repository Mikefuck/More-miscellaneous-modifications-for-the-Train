package com.habitrain.lottery.client.render;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCalendarIndexTest {

    @BeforeEach
    @AfterEach
    void reset() {
        LoginCalendarIndex.clear();
    }

    @Test
    void positionsOutsideRangeAreSkipped() {
        LoginCalendarIndex.add(new BlockPos(0, 64, 0));
        LoginCalendarIndex.add(new BlockPos(12, 64, 0));
        LoginCalendarIndex.add(new BlockPos(13, 64, 0));
        LoginCalendarIndex.add(new BlockPos(0, 64, 12));
        LoginCalendarIndex.add(new BlockPos(8, 72, 8));
        LoginCalendarIndex.add(new BlockPos(100, 64, 100));

        List<Long> inRange = new ArrayList<>();
        LoginCalendarIndex.collectInRange(0, 64, 0, inRange);
        Set<Long> packed = new HashSet<>(inRange);

        assertTrue(packed.contains(BlockPos.asLong(0, 64, 0)));
        assertTrue(packed.contains(BlockPos.asLong(12, 64, 0)));
        assertTrue(packed.contains(BlockPos.asLong(0, 64, 12)));
        assertFalse(packed.contains(BlockPos.asLong(13, 64, 0)));
        assertFalse(packed.contains(BlockPos.asLong(8, 72, 8)));
        assertFalse(packed.contains(BlockPos.asLong(100, 64, 100)));
        assertEquals(3, packed.size());
    }

    @Test
    void isInRenderRangeUsesDistanceSquared() {
        assertTrue(LoginCalendarIndex.isInRenderRange(12, 0, 0, 0, 0, 0));
        assertFalse(LoginCalendarIndex.isInRenderRange(13, 0, 0, 0, 0, 0));
        assertTrue(LoginCalendarIndex.isInRenderRange(0, 12, 0, 0, 0, 0));
        assertFalse(LoginCalendarIndex.isInRenderRange(7, 7, 7, 0, 0, 0));
        assertTrue(LoginCalendarIndex.isInRenderRange(6, 6, 6, 0, 0, 0));
        assertEquals(12, LoginCalendarIndex.RENDER_RANGE);
        assertTrue(LoginCalendarIndex.RENDER_RANGE <= 16);
    }

    @Test
    void emptyIndexCollectsNothing() {
        List<Long> out = new ArrayList<>();
        LoginCalendarIndex.collectInRange(0, 0, 0, out);
        assertTrue(out.isEmpty());
        assertTrue(LoginCalendarIndex.isEmpty());
    }

    @Test
    void removeDropsPositionFromRangeQuery() {
        BlockPos pos = new BlockPos(3, 70, 4);
        LoginCalendarIndex.add(pos);
        LoginCalendarIndex.add(new BlockPos(4, 70, 4));
        LoginCalendarIndex.remove(pos);

        List<Long> out = new ArrayList<>();
        LoginCalendarIndex.collectInRange(3, 70, 4, out);
        assertEquals(1, out.size());
        assertEquals(BlockPos.asLong(4, 70, 4), out.get(0));
        assertEquals(1, LoginCalendarIndex.size());
    }
}
