package com.habitrain.lottery.card;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CardUseGatesTest {

    @Test
    void lobbyAlivePlayerIsAllowed() {
        assertFalse(CardUseGates.shouldReject(false, false, false, false));
    }

    @Test
    void runningGameSpectatorDeadAndRestAreaAreRejected() {
        assertTrue(CardUseGates.shouldReject(false, false, false, true));
        assertTrue(CardUseGates.shouldReject(true, false, false, false));
        assertTrue(CardUseGates.shouldReject(false, true, false, false));
        assertTrue(CardUseGates.shouldReject(false, false, true, false));
        assertTrue(CardUseGates.REJECT_MESSAGE.contains("对局进行中"));
        assertEquals("§c[职业卡] 对局进行中不能使用职业卡", CardUseGates.REJECT_MESSAGE);
    }

    @Test
    void gameNotInactiveFailsClosedOnLookupFailure() {
        assertTrue(CardUseGates.gameNotInactive(true, false));
        assertTrue(CardUseGates.gameNotInactive(true, true));
        assertTrue(CardUseGates.gameNotInactive(false, true));
        assertFalse(CardUseGates.gameNotInactive(false, false));
        assertTrue(CardUseGates.shouldReject(false, false, false, CardUseGates.gameNotInactive(true, false)));
    }

    @Test
    void anyLevelNotInactiveORsPerLevelFlags() {
        assertFalse(CardUseGates.anyLevelNotInactive());
        assertFalse(CardUseGates.anyLevelNotInactive(false, false));
        assertTrue(CardUseGates.anyLevelNotInactive(false, true, false));
        assertTrue(CardUseGates.gameNotInactive(false, CardUseGates.anyLevelNotInactive(false, true)));
        assertFalse(CardUseGates.spectatorRestOrDead(false, false, false));
        assertTrue(CardUseGates.spectatorRestOrDead(false, true, false));
    }
}
