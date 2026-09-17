package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WinFactionRulesTest {

    @Test
    void blackoutMapsBadAndWrathOnKillerWinToKiller() {
        assertEquals(GrantEventHooks.WinFaction.KILLER, WinFactionRules.fromBlackoutName("BAD", true));
        assertEquals(GrantEventHooks.WinFaction.KILLER, WinFactionRules.fromBlackoutName("SIN_KILLER_SHARE", true));
        assertEquals(GrantEventHooks.WinFaction.NEUTRAL, WinFactionRules.fromBlackoutName("SIN_KILLER_SHARE", false));
        assertEquals(GrantEventHooks.WinFaction.NEUTRAL, WinFactionRules.fromBlackoutName("SIN_INDEPENDENT", true));
        assertEquals(GrantEventHooks.WinFaction.PASSENGER, WinFactionRules.fromBlackoutName("GOOD", false));
        assertNull(WinFactionRules.fromBlackoutName("UNKNOWN", true));
    }

    @Test
    void murderNeutralAndKillerFlags() {
        assertEquals(GrantEventHooks.WinFaction.NEUTRAL, WinFactionRules.fromRoleFlags(
                true, false, false, false, false, false, false, false));
        assertEquals(GrantEventHooks.WinFaction.NEUTRAL, WinFactionRules.fromRoleFlags(
                false, true, false, false, false, false, false, false));
        assertEquals(GrantEventHooks.WinFaction.KILLER, WinFactionRules.fromRoleFlags(
                false, false, false, false, true, false, true, false));
        assertEquals(GrantEventHooks.WinFaction.PASSENGER, WinFactionRules.fromRoleFlags(
                false, false, false, false, false, false, false, false));
    }

    @Test
    void unassignedPlayersDoNotInheritGlobalWinStatus() {
        assertTrue(WinFactionRules.isUnassignedForWinGrant(false, false));
        assertFalse(WinFactionRules.isUnassignedForWinGrant(true, false));
        assertFalse(WinFactionRules.isUnassignedForWinGrant(false, true));
        assertFalse(WinFactionRules.isUnassignedForWinGrant(true, true));
    }

    @Test
    void frozenProfileIgnoresMutatedKillerTeamBits() {
        assertEquals(GrantEventHooks.WinFaction.PASSENGER, WinFactionRules.fromProfile(
                false, false, false, false, false, false));
        assertEquals(GrantEventHooks.WinFaction.NEUTRAL, WinFactionRules.fromProfile(
                true, false, false, false, false, false));
        assertEquals(GrantEventHooks.WinFaction.NEUTRAL, WinFactionRules.fromProfile(
                false, true, false, false, false, false));
        assertEquals(GrantEventHooks.WinFaction.KILLER, WinFactionRules.fromProfile(
                false, false, false, false, true, false));
        assertEquals(GrantEventHooks.WinFaction.KILLER, WinFactionRules.fromProfile(
                false, false, false, false, false, true));
        assertEquals(GrantEventHooks.WinFaction.NEUTRAL, WinFactionRules.fromProfile(
                false, false, true, true, false, false));
        // Assigned-role fallback still sees killer/killerTeam; profile path does not.
        assertEquals(GrantEventHooks.WinFaction.KILLER, WinFactionRules.fromRoleFlags(
                false, false, false, false, true, true, false, false));
        assertNull(GrantEventHooks.classifyFromCatalog(null, null));
    }
}
