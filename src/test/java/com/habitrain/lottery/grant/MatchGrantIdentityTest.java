package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchGrantIdentityTest {

    @Test
    void sameDimAndStartTimeAreStableAcrossRestarts() {
        String a = MatchGrantIdentity.of("minecraft:overworld", 12345L);
        String b = MatchGrantIdentity.of("minecraft:overworld", 12345L);
        assertEquals("minecraft:overworld:12345", a);
        assertEquals(a, b);
        assertTrue(MatchGrantIdentity.isRecordedStart("minecraft:overworld", 12345L));
    }

    @Test
    void differentStartTimesAreUnique() {
        String first = MatchGrantIdentity.of("minecraft:overworld", 100L);
        String second = MatchGrantIdentity.of("minecraft:overworld", 200L);
        assertNotEquals(first, second);
    }

    @Test
    void missingStartFallsBackToUuid() {
        String key = MatchGrantIdentity.of(null, null);
        assertDoesNotThrow(() -> UUID.fromString(key));
        assertFalse(MatchGrantIdentity.isRecordedStart(null, 1L));
        assertFalse(MatchGrantIdentity.isRecordedStart("minecraft:overworld", null));
    }
}
