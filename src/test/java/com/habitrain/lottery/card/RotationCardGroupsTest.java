package com.habitrain.lottery.card;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationCardGroupsTest {

    @Test
    void forcedTypeMapsToCardGroup() {
        assertEquals(0, RotationCardGroups.fromForcedType(4));
        assertEquals(1, RotationCardGroups.fromForcedType(2));
        assertEquals(2, RotationCardGroups.fromForcedType(1));
        assertEquals(3, RotationCardGroups.fromForcedType(3));
        assertEquals(-1, RotationCardGroups.fromForcedType(null));
        assertEquals(-1, RotationCardGroups.fromForcedType(0));
        assertEquals(-1, RotationCardGroups.fromForcedType(5));
        assertEquals(-1, RotationCardGroups.fromForcedType(-1));
        assertEquals(-1, RotationCardGroups.fromForcedType(99));
    }

    @Test
    void matchesRoleTypePerCardGroup() {
        assertTrue(RotationCardGroups.matchesRoleType(0, 4));
        assertTrue(RotationCardGroups.matchesRoleType(1, 2));
        assertTrue(RotationCardGroups.matchesRoleType(2, 1));
        assertTrue(RotationCardGroups.matchesRoleType(3, 3));
        assertFalse(RotationCardGroups.matchesRoleType(0, 3));
        assertFalse(RotationCardGroups.matchesRoleType(1, 3));
        assertFalse(RotationCardGroups.matchesRoleType(3, 2));
        assertFalse(RotationCardGroups.matchesRoleType(-1, 4));
    }

    @Test
    void killerNeutralUsesKillerBandNotNoCard() {
        // n=10: killer band is index 4 (1-based order 5). Nine civilian cards
        // fill 70–100% then overflow; if group 3 were dumped into no-card it
        // would stay at 0 after civilians take every remaining slot.
        int n = 10;
        UUID killerNeutral = UUID.randomUUID();
        Map<UUID, Integer> order = new LinkedHashMap<>();
        order.put(killerNeutral, 0);
        Map<UUID, Integer> forced = new HashMap<>();
        forced.put(killerNeutral, 3);
        for (int i = 1; i < n; i++) {
            UUID civilian = UUID.randomUUID();
            order.put(civilian, 0);
            forced.put(civilian, 1);
        }

        RotationCardGroups.assignRotationOrder(order, forced::get);

        int killerStart = (int) Math.floor(n * 0.4);
        int killerEnd = Math.min((int) Math.ceil(n * 0.5), n);
        int assigned = order.get(killerNeutral);
        assertTrue(assigned > 0, "card-group 3 must not stay unassigned (0)");
        assertTrue(
                assigned >= killerStart + 1 && assigned <= killerEnd,
                "card-group 3 should sit in the killer band, got " + assigned);
    }

    @Test
    void killerNeutralOverflowsToNearestWhenKillerBandFull() {
        int n = 10;
        UUID killer = UUID.randomUUID();
        UUID killerNeutral = UUID.randomUUID();
        Map<UUID, Integer> order = new LinkedHashMap<>();
        order.put(killer, 0);
        order.put(killerNeutral, 0);
        for (int i = 0; i < n - 2; i++) {
            order.put(UUID.randomUUID(), 0);
        }
        Map<UUID, Integer> forced = new HashMap<>();
        forced.put(killer, 4);
        forced.put(killerNeutral, 3);

        RotationCardGroups.assignRotationOrder(order, forced::get);

        assertTrue(order.get(killer) > 0);
        assertTrue(order.get(killerNeutral) > 0, "overflow group 3 must fillNearest, not stay 0");
        assertAllPlayersHaveDistinctOneBasedSlots(order, n);
    }

    @Test
    void everyPlayerGetsADistinctOneBasedSlot() {
        int n = 8;
        Map<UUID, Integer> order = new LinkedHashMap<>();
        Map<UUID, Integer> forced = new HashMap<>();
        UUID killer = UUID.randomUUID();
        UUID killerNeutral = UUID.randomUUID();
        UUID neutral = UUID.randomUUID();
        UUID civilian = UUID.randomUUID();
        order.put(killer, 0);
        order.put(killerNeutral, 0);
        order.put(neutral, 0);
        order.put(civilian, 0);
        forced.put(killer, 4);
        forced.put(killerNeutral, 3);
        forced.put(neutral, 2);
        forced.put(civilian, 1);
        for (int i = 0; i < n - 4; i++) {
            order.put(UUID.randomUUID(), 0);
        }

        RotationCardGroups.assignRotationOrder(order, forced::get);

        assertAllPlayersHaveDistinctOneBasedSlots(order, n);
    }

    private static void assertAllPlayersHaveDistinctOneBasedSlots(Map<UUID, Integer> order, int n) {
        assertEquals(n, order.size());
        Set<Integer> slots = new HashSet<>();
        for (int v : order.values()) {
            assertTrue(v >= 1 && v <= n, "1-based slot, got " + v);
            assertTrue(slots.add(v), "duplicate slot " + v);
        }
        assertEquals(n, slots.size());
    }
}
