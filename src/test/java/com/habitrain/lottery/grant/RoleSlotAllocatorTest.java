package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RoleSlotAllocatorTest {
    record Role(String id, int faction) {}
    private static final Role CIVILIAN = new Role("civilian", 1);
    private static final Role KILLER = new Role("killer", 4);
    private static final Role SELECTED = new Role("selected", 4);
    private static final Role NEUTRAL = new Role("neutral", 2);

    private boolean assign(Map<String, Role> map, String player, Role role, Set<String> locked) {
        return RoleSlotAllocator.assign(map, player, role, Role::id, Role::faction, locked::contains);
    }

    @Test void zeroKillerSlotsRejectsWithoutMutation() {
        var map = new HashMap<>(Map.of("a", CIVILIAN, "b", CIVILIAN));
        var before = Map.copyOf(map);
        assertFalse(assign(map, "a", SELECTED, Set.of("a")));
        assertEquals(before, map);
    }

    @Test void reusesExistingFactionSlotAndTransfersCurrentRoleWithoutDuplication() {
        var map = new HashMap<>(Map.of("a", NEUTRAL, "b", KILLER, "c", CIVILIAN));
        assertTrue(assign(map, "a", SELECTED, Set.of("a")));
        assertEquals(Map.of("a", SELECTED, "b", NEUTRAL, "c", CIVILIAN), map);
    }

    @Test void secondSelfSelectCannotExceedOneKillerSlot() {
        var map = new HashMap<>(Map.of("a", CIVILIAN, "b", CIVILIAN, "c", KILLER));
        assertTrue(assign(map, "a", SELECTED, Set.of("a", "b")));
        var before = Map.copyOf(map);
        assertFalse(assign(map, "b", KILLER, Set.of("a", "b")));
        assertEquals(before, map);
    }

    @Test void sameFactionReplacementDoesNotTransferDisplacedSpecialRole() {
        var map = new HashMap<>(Map.of("a", KILLER, "b", CIVILIAN));
        assertTrue(assign(map, "a", SELECTED, Set.of("a")));
        assertEquals(Map.of("a", SELECTED, "b", CIVILIAN), map);
    }

    @Test void existingExactRoleIsSwappedRatherThanDuplicated() {
        var map = new HashMap<>(Map.of("a", KILLER, "b", SELECTED));
        assertTrue(assign(map, "a", SELECTED, Set.of("a")));
        assertEquals(Map.of("a", SELECTED, "b", KILLER), map);
    }

    @Test void protectedExactRoleCannotBeDuplicated() {
        var map = new HashMap<>(Map.of("a", KILLER, "b", SELECTED));
        var before = Map.copyOf(map);
        assertFalse(assign(map, "a", SELECTED, Set.of("a", "b")));
        assertEquals(before, map);
    }

    @Test void nonParticipantCannotBeInserted() {
        var map = new HashMap<>(Map.of("a", KILLER));
        assertFalse(assign(map, "spectator", SELECTED, Set.of()));
        assertEquals(Map.of("a", KILLER), map);
    }

    @Test void alreadyHonoredAssignmentIsIdempotent() {
        var map = new HashMap<>(Map.of("a", SELECTED, "b", CIVILIAN));
        assertTrue(assign(map, "a", SELECTED, Set.of("a")));
        assertEquals(Map.of("a", SELECTED, "b", CIVILIAN), map);
    }

    @Test void everySmallLobbyPreservesFactionCountsAndParticipants() {
        Role[] roles = {CIVILIAN, KILLER, SELECTED, NEUTRAL};
        for (Role a : roles) for (Role b : roles) for (Role c : roles) {
            for (Role desired : roles) for (int mask = 0; mask < 8; mask++) {
                var map = new HashMap<>(Map.of("a", a, "b", b, "c", c));
                var before = Map.copyOf(map);
                Set<String> locked = new java.util.HashSet<>();
                if ((mask & 1) != 0) locked.add("a");
                if ((mask & 2) != 0) locked.add("b");
                if ((mask & 4) != 0) locked.add("c");
                boolean accepted = assign(map, "a", desired, locked);
                assertEquals(before.keySet(), map.keySet());
                for (int faction : new int[]{1, 2, 4, 5}) {
                    assertEquals(before.values().stream().filter(r -> r.faction() == faction).count(),
                            map.values().stream().filter(r -> r.faction() == faction).count());
                }
                if (!accepted) assertEquals(before, map);
                for (String player : locked) {
                    if (!player.equals("a")) assertEquals(before.get(player), map.get(player));
                }
            }
        }
    }
}
