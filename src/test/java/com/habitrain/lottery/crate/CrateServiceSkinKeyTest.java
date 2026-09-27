package com.habitrain.lottery.crate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrateServiceSkinKeyTest {
    @Test void missingProviderKeysStillUseCanonicalTypeAndValidatedId() {
        assertEquals("revolver/legacy_skin", CrateService.normalizeSkinKey("GUN/Legacy_Skin"));
        assertThrows(IllegalArgumentException.class, () -> CrateService.normalizeSkinKey("other/skin"));
        assertThrows(IllegalArgumentException.class, () -> CrateService.normalizeSkinKey("knife/../bad"));
        assertThrows(IllegalArgumentException.class, () -> CrateService.normalizeSkinKey("knife/" + "x".repeat(49)));
    }
}
