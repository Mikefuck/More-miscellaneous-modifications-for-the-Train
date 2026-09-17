package com.habitrain.lottery.backpack;

import org.junit.jupiter.api.Test;

import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactionCardRoleTypesTest {

    @Test
    void mapsCardsToAssignerRoleTypes() {
        assertEquals(0, FactionCardRoleTypes.roleTypeId(FactionCardType.NONE));
        assertEquals(1, FactionCardRoleTypes.roleTypeId(FactionCardType.CIVILIAN));
        assertEquals(2, FactionCardRoleTypes.roleTypeId(FactionCardType.NEUTRAL));
        assertEquals(3, FactionCardRoleTypes.roleTypeId(FactionCardType.NEUTRAL_FOR_KILLER));
        assertEquals(4, FactionCardRoleTypes.roleTypeId(FactionCardType.KILLER));
    }

    @Test
    void reverseMappingRoundTrips() {
        for (FactionCardType type : FactionCardType.values()) {
            if (type == FactionCardType.NONE) {
                continue;
            }
            int id = FactionCardRoleTypes.roleTypeId(type);
            assertEquals(type, FactionCardRoleTypes.fromRoleTypeId(id), "round-trip for " + type);
        }
    }

    @Test
    void vanillaEnumIdsAreWrongAndMustNotBeUsed() {
        // Document the SRE bug: the enum's own `type` ids do NOT match the
        // assigner ids used by ForcePlayerTeam during rotation assignment.
        // New SRE (master) fixed NEUTRAL_FOR_KILLER 3→4 but kept the rest of
        // the enum space (1=killer, 2=civilian, 3=neutral, 4=neutral_for_killer),
        // which still collides with the assigner space
        // (1=civilian, 2=neutral, 3=neutral_for_killer, 4=killer).
        assertEquals(1, FactionCardType.KILLER.type);
        assertEquals(2, FactionCardType.CIVILIAN.type);
        assertEquals(3, FactionCardType.NEUTRAL.type);
        assertEquals(4, FactionCardType.NEUTRAL_FOR_KILLER.type);
        // Our bridge must map to the assigner space.
        assertEquals(4, FactionCardRoleTypes.roleTypeId(FactionCardType.KILLER));
        assertEquals(1, FactionCardRoleTypes.roleTypeId(FactionCardType.CIVILIAN));
    }
}
