package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WarehouseCloseKeyTest {
    @Test void normalEntryClosesOnInventoryKeyPress() {
        var key = new WarehouseCloseKey(false);
        assertTrue(key.press(true, false));
    }

    @Test void holdEntryIgnoresRepeatsUntilOpeningKeyIsReleased() {
        var key = new WarehouseCloseKey(true);
        for (int i = 0; i < 20; i++) assertFalse(key.press(true, false));
        key.release(true);
        assertTrue(key.press(true, false));
    }

    @Test void releasingAnotherKeyDoesNotUnlockHoldEntry() {
        var key = new WarehouseCloseKey(true);
        key.release(false);
        assertFalse(key.press(true, false));
        key.release(true);
        assertTrue(key.press(true, false));
    }

    @Test void searchInputNeverClosesWarehouse() {
        var key = new WarehouseCloseKey(false);
        for (int i = 0; i < 5; i++) {
            assertFalse(key.press(true, true));
            key.release(true);
        }
        assertTrue(key.press(true, false));
    }

    @Test void leavingSearchWhileHoldingKeyDoesNotCloseOnRepeat() {
        var key = new WarehouseCloseKey(false);
        assertFalse(key.press(true, true));
        assertFalse(key.press(true, false));
        key.release(true);
        assertTrue(key.press(true, false));
    }

    @Test void unrelatedKeysDoNotConsumeNextInventoryPress() {
        var key = new WarehouseCloseKey(false);
        assertFalse(key.press(false, false));
        assertFalse(key.press(false, true));
        assertTrue(key.press(true, false));
    }

    @Test void onePressCannotCloseTwice() {
        var key = new WarehouseCloseKey(false);
        assertTrue(key.press(true, false));
        assertFalse(key.press(true, false));
        key.release(true);
        assertTrue(key.press(true, false));
    }
}
