package com.habitrain.lottery.crate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrateOpenAccessTest {
    @Test void actualSpectatorCanOpenDuringMatchWithoutRelaxingOtherPlayers() {
        assertEquals("", CrateOpenAccess.rejection(true, true, true));
        assertEquals("crates.in_match", CrateOpenAccess.rejection(false, false, true));
        assertEquals("crates.unavailable", CrateOpenAccess.rejection(false, true, false));
        assertEquals("", CrateOpenAccess.rejection(false, false, false));
    }
}
