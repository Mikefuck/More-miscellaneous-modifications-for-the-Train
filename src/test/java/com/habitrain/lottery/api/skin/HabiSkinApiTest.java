package com.habitrain.lottery.api.skin;

import com.habitrain.lottery.testutil.TestRegistryResetExtension;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TestRegistryResetExtension.class)
class HabiSkinApiTest {

    @Test
    void registersLocallyAndRejectsConflictingDefinition() {
        SkinDefinition definition = SkinDefinition.builder("knife", "habitrain_api_test_blade", 0xFF123456)
                .model("habitrain_lottery", "item/test/api_blade")
                .build();

        assertTrue(HabiSkinApi.register(definition));
        assertFalse(HabiSkinApi.register(definition));
        assertEquals(0xFF123456,
                HabiSkinApi.getSkins("knife").get("habitrain_api_test_blade").color());
        assertEquals(definition, HabiSkinApi.find("knife", "habitrain_api_test_blade").orElseThrow());

        SkinDefinition conflict = SkinDefinition.builder("knife", "habitrain_api_test_blade", 0xFFFFFFFF)
                .model("habitrain_lottery", "item/test/api_blade")
                .build();
        assertThrows(IllegalStateException.class, () -> HabiSkinApi.register(conflict));
    }

    /** Audit F-10: lookup normalisation must match registration normalisation. */
    @Test
    void findNormalisesCaseWhitespaceAndGunAlias() {
        HabiSkinApi.register(SkinDefinition.builder("revolver", "habitrain_api_test_royal", 0)
                .model("habitrain_lottery", "item/test/api_royal")
                .build());

        assertTrue(HabiSkinApi.find("gun", "habitrain_api_test_royal").isPresent());
        assertTrue(HabiSkinApi.find(" Gun ", " habitrain_api_test_royal ").isPresent());
        assertTrue(HabiSkinApi.find("revolver", "HabiTrain_API_Test_Royal").isPresent());
        assertTrue(HabiSkinApi.find("knife", "habitrain_api_test_royal").isEmpty());
        assertTrue(HabiSkinApi.find("revolver", null).isEmpty());
        assertTrue(HabiSkinApi.find("not_a_type", "habitrain_api_test_royal").isEmpty());
    }

    /** Audit F-03: a failed registrar must be rollback-able provider by provider. */
    @Test
    void removeProviderRollsBackOnlyThatProvidersEntries() {
        String previous = HabiSkinApi.beginProvider("test_provider_good");
        try {
            assertTrue(HabiSkinApi.register(SkinDefinition.builder("bat", "habitrain_api_test_bat", 0)
                    .model("habitrain_lottery", "item/test/api_bat")
                    .build()));
        } finally {
            HabiSkinApi.endProvider(previous);
        }
        String otherPrevious = HabiSkinApi.beginProvider("test_provider_broken");
        try {
            assertTrue(HabiSkinApi.register(SkinDefinition.builder("bat", "habitrain_api_test_broken", 0)
                    .model("habitrain_lottery", "item/test/api_broken")
                    .build()));
        } finally {
            HabiSkinApi.endProvider(otherPrevious);
        }

        assertEquals(Optional.of("test_provider_broken"),
                HabiSkinApi.providerOf("bat", "habitrain_api_test_broken"));
        assertEquals(1, HabiSkinApi.removeProvider("test_provider_broken"));

        assertTrue(HabiSkinApi.find("bat", "habitrain_api_test_broken").isEmpty());
        assertTrue(HabiSkinApi.providerOf("bat", "habitrain_api_test_broken").isEmpty());
        assertTrue(HabiSkinApi.find("bat", "habitrain_api_test_bat").isPresent());
    }

    @Test
    void unregisterRemovesExactlyOneEntry() {
        HabiSkinApi.register(SkinDefinition.builder("hat", "habitrain_api_test_hat", 0)
                .model("habitrain_lottery", "item/test/api_hat")
                .build());

        assertTrue(HabiSkinApi.unregister("hat", "habitrain_api_test_hat"));
        assertFalse(HabiSkinApi.unregister("hat", "habitrain_api_test_hat"));
        assertTrue(HabiSkinApi.find("hat", "habitrain_api_test_hat").isEmpty());
        assertFalse(HabiSkinApi.unregister("hat", null));
    }

    @Test
    void skinItemsProviderRollbackAndGetSkinsOrderArePreserved() {
        ResourceLocation itemA = ResourceLocation.parse("habitrain_lottery:test_custom_weapon");
        ResourceLocation itemB = ResourceLocation.parse("habitrain_lottery:test_other_weapon");

        SkinRegistrationTransaction.run("provider_a", () -> {
            SkinItems.bindItem(itemA, "knife");
            HabiSkinApi.register(SkinDefinition.builder("knife", "zeta_blade", 1).build());
            HabiSkinApi.register(SkinDefinition.builder("knife", "alpha_blade", 2).build());
            HabiSkinApi.register(SkinDefinition.builder("knife", "mu_blade", 3).build());
        });
        SkinRegistrationTransaction.run("provider_b", () -> SkinItems.bindItem(itemB, "bat"));

        assertEquals(List.of("zeta_blade", "alpha_blade", "mu_blade"),
                List.copyOf(HabiSkinApi.getSkins("knife").keySet()),
                "HabiSkinApi.getSkins must preserve deterministic registration order");
        assertEquals("knife", SkinItems.bindings().get(itemA));
        assertEquals("bat", SkinItems.bindings().get(itemB));

        // Removing provider_a must release itemA so it can rebind to a different type, while preserving itemB!
        assertEquals(3, HabiSkinApi.removeProvider("provider_a"));
        assertNull(SkinItems.bindings().get(itemA));
        assertEquals("bat", SkinItems.bindings().get(itemB));

        SkinRegistrationTransaction.run("provider_a", () -> SkinItems.bindItem(itemA, "revolver"));
        assertEquals("revolver", SkinItems.bindings().get(itemA));

        // Transactional rollback must undo a failed bindItem inside SkinRegistrationTransaction
        ResourceLocation itemRollback = ResourceLocation.parse("habitrain_lottery:test_rollback_weapon");
        assertThrows(IllegalStateException.class, () -> SkinRegistrationTransaction.run("provider_rollback", () -> {
            SkinItems.bindItem(itemRollback, "grenade");
            throw new IllegalStateException("abort registration");
        }));
        assertNull(SkinItems.bindings().get(itemRollback));
    }
}
