package com.habitrain.lottery.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EconomyTakeoverGatesTest {

    @Test
    void blockServerWriteOnlyWhenTakeoverOffOnLogicalServer() {
        assertTrue(EconomyTakeoverGates.blockServerWrite(false, false, true));
        assertFalse(EconomyTakeoverGates.blockServerWrite(true, false, true));
        assertFalse(EconomyTakeoverGates.blockServerWrite(false, true, true));
        assertFalse(EconomyTakeoverGates.blockServerWrite(false, false, false));
        assertFalse(EconomyTakeoverGates.blockServerWrite(false, true, false));
    }
}
