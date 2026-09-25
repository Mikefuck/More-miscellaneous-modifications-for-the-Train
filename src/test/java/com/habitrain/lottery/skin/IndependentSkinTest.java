package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for the independent skin catalogue. */
class IndependentSkinTest {
    @Test
    void registeredSkinsResolveIndependently() {
        SkinDefinition definition = SkinDefinition.builder("hat", "independent_test_hat", 0xFF78B85A)
                .quality(SkinQuality.BLUE)
                .model("habitrain_lottery", "item/test/independent_hat")
                .build();

        assertTrue(HabiSkinApi.register(definition));
        assertEquals(Optional.of(definition), HabiSkinApi.find("hat", "independent_test_hat"));
        assertEquals(Optional.of(definition), HabiSkinApi.fromEntry("hat/independent_test_hat"));
        assertEquals(Optional.of(SkinQuality.BLUE), HabiSkinApi.quality("hat", "independent_test_hat"));
    }

    @Test
    void unknownEntriesFailClosed() {
        assertTrue(HabiSkinApi.fromEntry("hat/removed_provider_hat").isEmpty());
        assertTrue(HabiSkinApi.fromEntry("hat/arbitrary/path").isEmpty());
        assertTrue(HabiSkinApi.find("hat", "removed_provider_hat").isEmpty());
    }

    @Test
    void providerRollbackRemovesOnlyItsIndependentRegistrations() {
        String previous = HabiSkinApi.beginProvider("independent_skin_test");
        try {
            assertTrue(HabiSkinApi.register(SkinDefinition.builder("knife", "independent_provider_skin", 0)
                    .model("habitrain_lottery", "item/test/provider_skin")
                    .build()));
        } finally {
            HabiSkinApi.endProvider(previous);
        }

        assertTrue(HabiSkinApi.find("knife", "independent_provider_skin").isPresent());
        assertEquals(1, HabiSkinApi.removeProvider("independent_skin_test"));
        assertTrue(HabiSkinApi.find("knife", "independent_provider_skin").isEmpty());
    }
}
