package com.habitrain.lottery.api.skin;

import io.wifi.starrailexpress.util.ItemSkinManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HabiSkinApiTest {

    @Test
    void registersIntoSreAndRejectsConflictingDefinition() {
        SkinDefinition definition = SkinDefinition.builder("knife", "habitrain_api_test_blade", 0xFF123456)
                .model("habitrain_lottery", "item/test/api_blade")
                .build();

        assertTrue(HabiSkinApi.register(definition));
        assertFalse(HabiSkinApi.register(definition));
        assertEquals(0xFF123456,
                ItemSkinManager.getSkins("knife").get("habitrain_api_test_blade").getColor());
        assertEquals(definition, HabiSkinApi.find("knife", "habitrain_api_test_blade").orElseThrow());

        SkinDefinition conflict = SkinDefinition.builder("knife", "habitrain_api_test_blade", 0xFFFFFFFF)
                .model("habitrain_lottery", "item/test/api_blade")
                .build();
        assertThrows(IllegalStateException.class, () -> HabiSkinApi.register(conflict));
    }
}
