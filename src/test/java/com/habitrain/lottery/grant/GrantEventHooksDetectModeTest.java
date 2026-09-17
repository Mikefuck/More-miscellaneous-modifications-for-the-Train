package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrantEventHooksDetectModeTest {

    @Test
    void detectModeFromNamesRecognizesKnownModes() {
        assertEquals("murder", GrantEventHooks.detectModeFromNames("SREMurderGameMode", null));
        assertEquals("blackout", GrantEventHooks.detectModeFromNames("BlackoutMode", "x"));
        assertEquals("repair", GrantEventHooks.detectModeFromNames("RepairEscape", null));
        assertEquals("murder", GrantEventHooks.detectModeFromNames("Other", "sre:murder"));
        assertNull(GrantEventHooks.detectModeFromNames("FunnyRotation", "role_rotation"));
    }

    @Test
    void fallbackModeUnknownIsNotMurder() {
        assertEquals("unknown", GrantEventHooks.fallbackMode(false));
        assertEquals("blackout", GrantEventHooks.fallbackMode(true));
        assertNotEquals("murder", GrantEventHooks.fallbackMode(false));
    }

    @Test
    void classifyModeIdUnknownDoesNotBecomeMurder() {
        assertEquals("unknown", GrantEventHooks.classifyModeId("unknown"));
        assertEquals("unknown", GrantEventHooks.classifyModeId(""));
        assertEquals("unknown", GrantEventHooks.classifyModeId(null));
        assertEquals("murder", GrantEventHooks.classifyModeId("sre:murder"));
        assertEquals("blackout", GrantEventHooks.classifyModeId("habitrain:blackout"));
        assertEquals("repair", GrantEventHooks.classifyModeId("sre:repair"));
    }
}
