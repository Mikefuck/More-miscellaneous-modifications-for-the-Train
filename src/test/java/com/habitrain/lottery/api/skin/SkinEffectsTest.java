package com.habitrain.lottery.api.skin;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registration/normalisation semantics of the skin effects API. Dispatch itself needs a
 * live level, so it is covered by the in-game checklist in {@code docs/skin-effects-api.md}.
 */
class SkinEffectsTest {

    private static final String TYPE = "grenade";
    private static final ResourceLocation PLAIN_GRENADE =
            ResourceLocation.parse("trainmurdermystery:grenade");

    private static void registerSkin(String id) {
        HabiSkinApi.register(SkinDefinition.builder(TYPE, id, 0xFF8844FF)
                .model("habitrain_lottery", "item/test/" + id)
                .build());
    }

    @Test
    void animationRegistrationNormalisesTypeAndId() {
        registerSkin("effects_anim");
        SkinAnimation animation = SkinAnimation.builder()
                .spin(SkinAnimation.SpinAxis.Y, 4.0F)
                .thrownSpinMultiplier(3.0F)
                .build();

        SkinEffects.registerAnimation(" Grenade ", " Effects_Anim ", animation);

        assertEquals(Optional.of(animation), SkinEffects.animation(TYPE, "effects_anim"));
        assertEquals(Optional.of(animation), SkinEffects.animationByEntry("grenade/effects_anim"));
        // Unknown skins read as empty rather than throwing.
        assertTrue(SkinEffects.animation(TYPE, "effects_missing").isEmpty());
        assertTrue(SkinEffects.animation("not_a_type", "effects_anim").isEmpty());
        assertTrue(SkinEffects.animationByEntry(null).isEmpty());
    }

    @Test
    void effectRegistrationRejectsInvalidKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> SkinEffects.registerImpact("not_a_type", "effects_bad", context -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> SkinEffects.registerTrail(TYPE, "has/slash", (level, projectile, position, partial) -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> SkinEffects.registerAnimation(TYPE, "coin", SkinAnimation.none()));
        assertThrows(NullPointerException.class,
                () -> SkinEffects.registerImpact(TYPE, "effects_null", null));
    }

    @Test
    void impactRegistrationMarksTheEntryAsReplacingTheVanillaBurst() {
        registerSkin("effects_impact");
        String entry = "grenade/effects_impact";

        assertFalse(SkinEffects.hasImpact(entry));
        assertFalse(SkinEffects.replacesVanillaBurst(entry, null));

        SkinEffects.registerImpact(TYPE, "effects_impact", context -> { });

        assertTrue(SkinEffects.hasImpact(entry));
        assertNotNull(SkinEffects.impactByEntry(entry));
        // A null stack still resolves: the item restriction is the only stack-dependent part.
        assertTrue(SkinEffects.replacesVanillaBurst(entry, null));
        assertTrue(SkinEffects.entries().contains(entry));
        assertTrue(SkinEffects.size() >= 1);
    }

    @Test
    void itemRestrictionDefaultsToUnrestrictedAndCanBeCleared() {
        registerSkin("effects_items");
        String entry = "grenade/effects_items";

        assertTrue(SkinEffects.itemFilter(entry).isEmpty());
        assertTrue(SkinEffects.allowsItemEntry(entry, null));

        SkinEffects.restrictToItems(TYPE, "effects_items", PLAIN_GRENADE);

        assertTrue(SkinEffects.itemFilter(entry).isPresent());
        assertEquals(1, SkinEffects.itemFilter(entry).orElseThrow().size());
        // A null item is not on the list, so it is refused. (The built-in triggers always
        // resolve the entry from a real stack, so a restricted skin can never slip through
        // as "unknown item" in practice.)
        assertFalse(SkinEffects.allowsItemEntry(entry, null));

        SkinEffects.restrictToItems(TYPE, "effects_items");
        assertTrue(SkinEffects.itemFilter(entry).isEmpty());
        assertTrue(SkinEffects.allowsItemEntry(entry, null));
    }

    @Test
    void providerRollbackRemovesEveryKindOfEffect() {
        String previous = HabiSkinApi.beginProvider("effects_test_provider");
        try {
            HabiSkinApi.register(SkinDefinition.builder(TYPE, "effects_rollback", 0)
                    .model("habitrain_lottery", "item/test/effects_rollback")
                    .build());
            SkinEffects.registerImpact(TYPE, "effects_rollback", context -> { });
            SkinEffects.registerTrail(TYPE, "effects_rollback",
                    (level, projectile, position, partial) -> { });
            SkinEffects.registerAnimation(TYPE, "effects_rollback",
                    SkinAnimation.builder().spin(SkinAnimation.SpinAxis.Y, 1.0F).build());
            SkinEffects.restrictToItems(TYPE, "effects_rollback", PLAIN_GRENADE);
        } finally {
            HabiSkinApi.endProvider(previous);
        }
        String entry = "grenade/effects_rollback";
        assertTrue(SkinEffects.hasImpact(entry));

        assertEquals(4, SkinEffects.removeProvider("effects_test_provider"));

        assertFalse(SkinEffects.hasImpact(entry));
        assertNull(SkinEffects.trailByEntry(entry));
        assertTrue(SkinEffects.animationByEntry(entry).isEmpty());
        assertTrue(SkinEffects.itemFilter(entry).isEmpty());
        // The skin itself belongs to the same provider and is rolled back by HabiSkinApi.
        assertEquals(1, HabiSkinApi.removeProvider("effects_test_provider"));
        assertTrue(HabiSkinApi.find(TYPE, "effects_rollback").isEmpty());
    }

    @Test
    void trailRegistrationIsVisibleToTheClientFastPath() {
        registerSkin("effects_trail");
        String entry = "grenade/effects_trail";
        assertNull(SkinEffects.trailByEntry(entry));

        SkinEffects.registerTrail(TYPE, "effects_trail", (level, projectile, position, partial) -> { });

        assertTrue(SkinEffects.hasTrails());
        assertNotNull(SkinEffects.trailByEntry(entry));
        assertNull(SkinEffects.trailByEntry(null));
        assertNull(SkinEffects.impactByEntry(null));
    }
}
