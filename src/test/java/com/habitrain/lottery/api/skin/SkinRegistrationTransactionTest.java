package com.habitrain.lottery.api.skin;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SkinRegistrationTransactionTest {
    private static final String ID = "transaction_replacement";
    private static final String OTHER_ID = "transaction_label_only";
    private static final String PROVIDER = "replacement_provider";
    private static final String OTHER_PROVIDER = "other_replacement_provider";

    @AfterEach void cleanup() {
        HabiSkinApi.unregister("knife", ID);
        SkinCardText.unregister("knife", OTHER_ID);
        SkinEffects.removeProvider(PROVIDER);
        SkinEffects.removeProvider(OTHER_PROVIDER);
        SkinSounds.removeProvider(PROVIDER);
        SkinSounds.removeProvider(OTHER_PROVIDER);
    }

    @Test void failedDefinitionReplacementRestoresSkinOwnerAndDependentDeclarations() {
        SkinDefinition original = registerOriginal();
        assertThrows(IllegalStateException.class, () -> SkinRegistrationTransaction.run(PROVIDER, () -> {
            assertTrue(HabiSkinApi.unregister("knife", ID));
            HabiSkinApi.register(SkinDefinition.builder("knife", ID, 2).build());
            SkinCardText.register("knife", ID, "new text");
            throw new IllegalStateException("failed after replacing skin");
        }));
        assertEquals(Optional.of(original), HabiSkinApi.find("knife", ID));
        assertEquals(Optional.of(PROVIDER), HabiSkinApi.providerOf("knife", ID));
        assertEquals("old text", SkinCardText.find("knife", ID).orElseThrow().getString());
        assertTrue(SkinSounds.supports("knife", ID));
    }

    @Test void failedTextReplacementRestoresOldText() {
        registerOriginal();
        assertThrows(IllegalStateException.class, () -> SkinRegistrationTransaction.run(PROVIDER, () -> {
            SkinCardText.unregister("knife", ID);
            SkinCardText.register("knife", ID, "new text");
            throw new IllegalStateException("failed after replacing label");
        }));
        assertEquals("old text", SkinCardText.find("knife", ID).orElseThrow().getString());
        assertEquals(1, SkinCardText.removeProvider(PROVIDER), "Restored label keeps its provider");
    }

    @Test void failedProviderRemovalRestoresAllDeclarationsIncludingForeignLabel() {
        SkinDefinition original = registerOriginal();
        SkinCardText.unregister("knife", ID);
        SkinRegistrationTransaction.run(OTHER_PROVIDER, () -> SkinCardText.register("knife", ID, "foreign label"));
        SkinRegistrationTransaction.run(PROVIDER, () -> SkinCardText.register("knife", OTHER_ID, "standalone label"));
        assertThrows(IllegalStateException.class, () -> SkinRegistrationTransaction.run(PROVIDER, () -> {
            assertEquals(1, HabiSkinApi.removeProvider(PROVIDER));
            assertFalse(SkinSounds.supports("knife", ID));
            assertTrue(SkinCardText.find("knife", OTHER_ID).isEmpty());
            throw new IllegalStateException("failed after provider removal");
        }));
        assertEquals(Optional.of(original), HabiSkinApi.find("knife", ID));
        assertTrue(SkinSounds.supports("knife", ID));
        assertEquals("foreign label", SkinCardText.find("knife", ID).orElseThrow().getString());
        assertEquals("standalone label", SkinCardText.find("knife", OTHER_ID).orElseThrow().getString());
        assertEquals(1, SkinCardText.removeProvider(OTHER_PROVIDER));
        assertEquals(1, SkinCardText.removeProvider(PROVIDER));
    }

    @Test void failedEffectRemovalRestoresHandlersFiltersAndTheirOwners() {
        registerOriginal();
        SkinImpactHandler impact = context -> { };
        SkinTrailHandler trail = (level, projectile, position, partial) -> { };
        SkinAnimation animation = SkinAnimation.builder().bob(1, 80).build();
        ResourceLocation item = ResourceLocation.parse("minecraft:iron_sword");
        SkinRegistrationTransaction.run(PROVIDER, () -> {
            SkinEffects.registerImpact("knife", ID, impact);
            SkinEffects.registerAnimation("knife", ID, animation);
            SkinEffects.restrictToItems("knife", ID, item);
        });
        SkinRegistrationTransaction.run(OTHER_PROVIDER, () -> SkinEffects.registerTrail("knife", ID, trail));
        assertThrows(IllegalStateException.class, () -> SkinRegistrationTransaction.run(PROVIDER, () -> {
            assertEquals(3, SkinEffects.removeProvider(PROVIDER));
            SkinEffects.registerTrail("knife", ID, (a, b, c, d) -> { });
            throw new IllegalStateException("failed after replacing effects");
        }));
        assertEquals(Optional.of(impact), SkinEffects.impact("knife", ID));
        assertEquals(Optional.of(trail), SkinEffects.trail("knife", ID));
        assertEquals(Optional.of(animation), SkinEffects.animation("knife", ID));
        assertEquals(Optional.of(Set.of(item)), SkinEffects.itemFilter("knife/" + ID));
        assertEquals(3, SkinEffects.removeProvider(PROVIDER));
        assertEquals(Optional.of(trail), SkinEffects.trail("knife", ID));
        assertEquals(1, SkinEffects.removeProvider(OTHER_PROVIDER));
    }

    @Test void failedNestedReplayRestoresDirectSoundAndLabelRemovals() {
        registerOriginal();
        assertThrows(IllegalStateException.class, () -> SkinRegistrationTransaction.run(PROVIDER, () -> {
            SkinRegistrationTransaction.run(OTHER_PROVIDER, () -> {
                assertEquals(1, SkinSounds.removeProvider(PROVIDER));
                assertEquals(1, SkinCardText.removeProvider(PROVIDER));
                SkinCardText.register("knife", ID, "nested replacement");
            });
            throw new IllegalStateException("outer replay failed");
        }));
        assertTrue(SkinSounds.supports("knife", ID));
        assertEquals("old text", SkinCardText.find("knife", ID).orElseThrow().getString());
        assertEquals(0, SkinCardText.removeProvider(OTHER_PROVIDER));
        assertEquals(1, SkinSounds.removeProvider(PROVIDER));
        assertNull(HabiSkinApi.currentProvider());
    }

    @Test void successfulUnregisterCommitsDeletion() {
        registerOriginal();
        SkinRegistrationTransaction.run(PROVIDER, () -> HabiSkinApi.unregister("knife", ID));
        assertTrue(HabiSkinApi.find("knife", ID).isEmpty());
        assertTrue(SkinCardText.find("knife", ID).isEmpty());
        assertFalse(SkinSounds.supports("knife", ID));
        assertTrue(HabiSkinApi.providerOf("knife", ID).isEmpty());
    }

    private SkinDefinition registerOriginal() {
        SkinDefinition original = SkinDefinition.builder("knife", ID, 1).build();
        SkinRegistrationTransaction.run(PROVIDER, () -> {
            HabiSkinApi.register(original);
            SkinCardText.register("knife", ID, "old text");
            SkinSounds.register("knife", ID);
        });
        return original;
    }
}
