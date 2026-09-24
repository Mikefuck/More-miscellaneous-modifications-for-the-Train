package com.habitrain.lottery.api.skin;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkinDefinitionTest {

    @Test
    void legacyBuildersAndConstructorDefaultToWhiteRegardlessOfAccent() {
        var old = SkinDefinition.builder("knife", "legacy", SkinQuality.RED.color()).build();
        assertEquals(SkinQuality.WHITE, old.quality());
        assertEquals(old, new SkinDefinition(old.type(), old.id(), old.color(), old.model(), old.lotteryPlacements()));
    }

    @Test
    void providerChoosesQualityWithoutChangingPoolPlacementOrAccent() {
        for (var quality : SkinQuality.values()) {
            var skin = SkinDefinition.builder("knife", "quality_test", 0xFF34E29C)
                    .quality(quality).addToPool("knife", 5).build();
            assertEquals(quality, skin.quality());
            assertEquals(0xFF34E29C, skin.color());
            assertEquals(5, skin.lotteryPlacements().getFirst().qualityBand());
            assertEquals(quality, SkinDefinition.builder("knife", "shorthand", quality).build().quality());
            assertEquals(quality, SkinQuality.fromId(quality.id()));
        }
        assertEquals(SkinQuality.WHITE, SkinQuality.fromId("future_quality"));
        assertEquals(SkinQuality.WHITE, SkinQuality.fromId(null));
        assertThrows(NullPointerException.class, () -> SkinDefinition.builder("knife", "test", 0).quality(null));
    }

    @Test
    void builderNormalizesGunAndResolvesCustomModelVariants() {
        SkinDefinition definition = SkinDefinition.builder("GUN", " Crystal_One ", 0xFF33AAFF)
                .model("example", "item/skins/revolver/crystal_one")
                .includeInDefaultPools()
                .build();

        assertEquals("revolver", definition.type());
        assertEquals("crystal_one", definition.id());
        assertEquals("gun/crystal_one", definition.lotteryEntry());
        assertEquals(ResourceLocation.parse("example:item/skins/revolver/crystal_one"), definition.model(false));
        assertEquals(ResourceLocation.parse("example:item/skins/revolver/crystal_one_in_hand"), definition.model(true));
        assertEquals(2, definition.lotteryPlacements().size());
    }

    @Test
    void rejectsUnsafeIdsAndSupportsHatLotteryPlacement() {
        assertThrows(IllegalArgumentException.class,
                () -> SkinDefinition.builder("knife", "example:bad", 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinDefinition.builder("unknown", "valid", 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinDefinition.builder("knife", "default", 0).build());
        assertEquals(2, SkinDefinition.builder("hat", "crown", 0).includeInDefaultPools().build().lotteryPlacements().size());
    }

    /** Audit F-04: the v1 default model namespace stays reachable for un-recompiled extensions. */
    @Test
    void defaultModelUsesCurrentNamespaceAndStillExposesTheV1Fallback() {
        SkinDefinition definition = SkinDefinition.builder("bat", "example_star_bat", 0).build();

        assertTrue(definition.usesDefaultModel());
        assertEquals(ResourceLocation.parse("habitrain_lottery:item/skins/bat/example_star_bat"),
                definition.model(false));
        assertEquals(ResourceLocation.parse("starrailexpress:bat/example_star_bat"),
                definition.legacyModel(false));
        assertEquals(ResourceLocation.parse("starrailexpress:bat/example_star_bat_in_hand"),
                definition.legacyModel(true));
    }

    @Test
    void explicitModelHasNoLegacyFallback() {
        SkinDefinition definition = SkinDefinition.builder("knife", "example_crystal", 0)
                .model("example", "item/skins/knife/crystal")
                .build();

        assertFalse(definition.usesDefaultModel());
        assertEquals(ResourceLocation.parse("example:item/skins/knife/crystal"), definition.model(false));
        assertNull(definition.legacyModel(false));
        assertNull(definition.legacyModel(true));
    }

    /** Audit F-10: registration, lookup and mail all share one id normaliser. */
    @Test
    void sharedIdNormaliserTrimsFoldsCaseAndRejectsUnsafeValues() {
        assertEquals("crystal_blade", SkinDefinition.normalizeSkinId(" Crystal_Blade "));
        assertNull(SkinDefinition.normalizeSkinId(null));
        assertNull(SkinDefinition.normalizeSkinId("   "));
        assertThrows(IllegalArgumentException.class, () -> SkinDefinition.normalizeSkinId("default"));
        assertThrows(IllegalArgumentException.class, () -> SkinDefinition.normalizeSkinId("coin"));
        assertThrows(IllegalArgumentException.class, () -> SkinDefinition.normalizeSkinId("example:bad"));
        assertThrows(IllegalArgumentException.class, () -> SkinDefinition.normalizeSkinId("a".repeat(49)));
        assertEquals("a".repeat(48), SkinDefinition.normalizeSkinId("a".repeat(48)));
    }
}
