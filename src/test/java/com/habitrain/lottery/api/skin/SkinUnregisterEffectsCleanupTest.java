package com.habitrain.lottery.api.skin;

import static org.junit.jupiter.api.Assertions.*;

import com.habitrain.lottery.skin.SkinContentBootstrap;
import com.habitrain.lottery.testutil.TestRegistryResetExtension;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression coverage for Audit B-18 & I-20: {@link HabiSkinApi#unregister} and
 * {@link HabiSkinApi#removeProvider} clean up {@link SkinEffects}, {@link SkinSounds},
 * {@link SkinCardText}, and {@code REPORTED_FAILURES}, and {@link SkinContentBootstrap#runProviderTransaction}
 * reloads modified provider definitions cleanly while restoring prior state on failure.
 */
@ExtendWith(TestRegistryResetExtension.class)
class SkinUnregisterEffectsCleanupTest {

    private static final ResourceLocation GRENADE_ITEM = ResourceLocation.parse("trainmurdermystery:grenade");

    @Test
    void unregisterClearsEffectsSoundsCardTextAndReportedFailures() {
        HabiSkinApi.register(SkinDefinition.builder("grenade", "cleanup_one", 0xFF112233)
                .model("habitrain_lottery", "item/test/cleanup_one")
                .build());
        SkinEffects.registerImpact("grenade", "cleanup_one", ctx -> { });
        SkinEffects.registerTrail("grenade", "cleanup_one", (lvl, proj, pos, pt) -> { });
        SkinEffects.registerAnimation("grenade", "cleanup_one",
                SkinAnimation.builder().spin(SkinAnimation.SpinAxis.Y, 2.0F).build());
        SkinEffects.restrictToItems("grenade", "cleanup_one", GRENADE_ITEM);
        SkinSounds.register("grenade", "cleanup_one");
        SkinCardText.register("grenade", "cleanup_one", "限定");
        SkinEffects.reportFailure("grenade/cleanup_one", "impact", new RuntimeException("simulated"));

        assertTrue(SkinEffects.hasImpact("grenade/cleanup_one"));
        assertTrue(SkinEffects.replacesVanillaBurst("grenade/cleanup_one", null));
        assertNotNull(SkinEffects.trailByEntry("grenade/cleanup_one"));
        assertTrue(SkinEffects.animationByEntry("grenade/cleanup_one").isPresent());
        assertTrue(SkinEffects.itemFilter("grenade/cleanup_one").isPresent());
        assertTrue(SkinSounds.supports("grenade", "cleanup_one"));
        assertTrue(SkinCardText.find("grenade", "cleanup_one").isPresent());
        assertTrue(SkinEffects.hasReportedFailure("grenade/cleanup_one", "impact"));

        assertTrue(HabiSkinApi.unregister("grenade", "cleanup_one"));

        assertFalse(SkinEffects.hasImpact("grenade/cleanup_one"));
        assertFalse(SkinEffects.replacesVanillaBurst("grenade/cleanup_one", null));
        assertNull(SkinEffects.trailByEntry("grenade/cleanup_one"));
        assertTrue(SkinEffects.animationByEntry("grenade/cleanup_one").isEmpty());
        assertTrue(SkinEffects.itemFilter("grenade/cleanup_one").isEmpty());
        assertFalse(SkinSounds.supports("grenade", "cleanup_one"));
        assertTrue(SkinCardText.find("grenade", "cleanup_one").isEmpty());
        assertFalse(SkinEffects.hasReportedFailure("grenade/cleanup_one", "impact"));
    }

    @Test
    void removeProviderClearsOwnedEffectsAndSoundsWhilePreservingOtherProviders() {
        SkinRegistrationTransaction.run("provider_keep", () -> {
            HabiSkinApi.register(SkinDefinition.builder("grenade", "shared_orb", 0xFF00AA00)
                    .model("habitrain_lottery", "item/test/shared_orb")
                    .build());
            SkinEffects.registerImpact("grenade", "shared_orb", ctx -> { });
            SkinSounds.register("grenade", "shared_orb");
            SkinCardText.register("grenade", "shared_orb", "保留角标");
        });

        SkinRegistrationTransaction.run("provider_drop", () -> {
            HabiSkinApi.register(SkinDefinition.builder("grenade", "drop_orb", 0xFFAA0000)
                    .model("habitrain_lottery", "item/test/drop_orb")
                    .build());
            SkinEffects.registerImpact("grenade", "drop_orb", ctx -> { });
            SkinSounds.register("grenade", "drop_orb");
            SkinCardText.register("grenade", "drop_orb", "移除角标");
            // Provider_drop also attaches a client trail onto provider_keep's skin
            SkinEffects.registerTrail("grenade", "shared_orb", (lvl, proj, pos, pt) -> { });
        });

        assertNotNull(SkinEffects.trailByEntry("grenade/shared_orb"));
        assertEquals(1, HabiSkinApi.removeProvider("provider_drop"));

        // provider_drop's own skin & sub-registries are gone, plus its trail on shared_orb
        assertTrue(HabiSkinApi.find("grenade", "drop_orb").isEmpty());
        assertFalse(SkinEffects.hasImpact("grenade/drop_orb"));
        assertFalse(SkinSounds.supports("grenade", "drop_orb"));
        assertTrue(SkinCardText.find("grenade", "drop_orb").isEmpty());
        assertNull(SkinEffects.trailByEntry("grenade/shared_orb"));

        // provider_keep's skin, impact, sound, and card text remain intact
        assertTrue(HabiSkinApi.find("grenade", "shared_orb").isPresent());
        assertTrue(SkinEffects.hasImpact("grenade/shared_orb"));
        assertTrue(SkinSounds.supports("grenade", "shared_orb"));
        assertEquals("保留角标", SkinCardText.find("grenade", "shared_orb").orElseThrow().getString());
    }

    @Test
    void reregisteringHandlerClearsReportedFailureFlag() {
        HabiSkinApi.register(SkinDefinition.builder("grenade", "flaky_orb", 0xFF336699)
                .model("habitrain_lottery", "item/test/flaky_orb")
                .build());
        SkinEffects.registerImpact("grenade", "flaky_orb", ctx -> { });
        SkinEffects.reportFailure("grenade/flaky_orb", "impact", new RuntimeException("first failure"));
        assertTrue(SkinEffects.hasReportedFailure("grenade/flaky_orb", "impact"));

        // Re-registering a fixed handler clears the once-per-entry suppression flag.
        SkinEffects.registerImpact("grenade", "flaky_orb", ctx -> { });
        assertFalse(SkinEffects.hasReportedFailure("grenade/flaky_orb", "impact"));
    }

    @Test
    void skinContentBootstrapRunProviderTransactionReloadsModifiedDefinitionsAndRestoresOnFailure() {
        // Initial v1 load
        SkinContentBootstrap.runProviderTransaction("ext_mod", () -> {
            HabiSkinApi.register(SkinDefinition.builder("knife", "evolving_blade", 0xFF111111)
                    .model("habitrain_lottery", "item/test/blade_v1")
                    .build());
            HabiSkinApi.register(SkinDefinition.builder("knife", "retired_blade", 0xFF999999)
                    .model("habitrain_lottery", "item/test/retired")
                    .build());
            SkinSounds.register("knife", "evolving_blade");
            SkinCardText.register("knife", "evolving_blade", "V1");
        });
        assertEquals(0xFF111111, HabiSkinApi.find("knife", "evolving_blade").orElseThrow().color());
        assertTrue(HabiSkinApi.find("knife", "retired_blade").isPresent());
        assertTrue(SkinSounds.supports("knife", "evolving_blade"));

        // v2 reload with modified color/model/cardText and retired_blade removed -> must succeed without conflict!
        SkinContentBootstrap.runProviderTransaction("ext_mod", () -> {
            HabiSkinApi.register(SkinDefinition.builder("knife", "evolving_blade", 0xFF222222)
                    .model("habitrain_lottery", "item/test/blade_v2")
                    .build());
            SkinCardText.register("knife", "evolving_blade", "V2");
        });
        assertEquals(0xFF222222, HabiSkinApi.find("knife", "evolving_blade").orElseThrow().color());
        assertTrue(HabiSkinApi.find("knife", "retired_blade").isEmpty());
        assertFalse(SkinSounds.supports("knife", "evolving_blade"));
        assertEquals("V2", SkinCardText.find("knife", "evolving_blade").orElseThrow().getString());

        // v3 broken reload throws midway -> must roll back and restore v2 completely!
        assertThrows(IllegalStateException.class, () ->
                SkinContentBootstrap.runProviderTransaction("ext_mod", () -> {
                    HabiSkinApi.register(SkinDefinition.builder("knife", "evolving_blade", 0xFF333333)
                            .model("habitrain_lottery", "item/test/blade_v3")
                            .build());
                    SkinCardText.register("knife", "evolving_blade", "V3");
                    throw new IllegalStateException("broken reload");
                }));

        assertEquals(Optional.of("ext_mod"), HabiSkinApi.providerOf("knife", "evolving_blade"));
        assertEquals(0xFF222222, HabiSkinApi.find("knife", "evolving_blade").orElseThrow().color());
        assertEquals("V2", SkinCardText.find("knife", "evolving_blade").orElseThrow().getString());
    }
}
