package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GrantEventHooksMatchKeyTest {
    @Test
    void newMatchKeyIsDimensionAndStartTick() {
        String a = GrantEventHooks.newMatchKey("minecraft:overworld", 12345L, 99L);
        String b = GrantEventHooks.newMatchKey("minecraft:overworld", 12346L, 99L);
        assertEquals("minecraft:overworld|12345|99", a);
        assertNotEquals(a, b);
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString(a));
    }

    @Test
    void fingerprintUsesStartWorldTickNotProcessSeq() {
        String fp = GrantEventHooks.formatRoundFingerprint("minecraft:overworld", 12345L, 99L);
        assertEquals("minecraft:overworld|12345|99", fp);
        assertNotEquals("1", fp);
        assertFalse(fp.endsWith(":1"));
    }

    @Test
    void nullMatchWinFactionDoesNotDefaultToPassenger() {
        assertNull(GrantEventHooks.WinFaction.from(null));
    }
}
