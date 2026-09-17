package com.habitrain.lottery.api.skin;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkinDefinitionTest {

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
    void rejectsUnsafeIdsUnsupportedTypesAndHatLotteryPlacement() {
        assertThrows(IllegalArgumentException.class,
                () -> SkinDefinition.builder("knife", "example:bad", 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinDefinition.builder("unknown", "valid", 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinDefinition.builder("knife", "default", 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinDefinition.builder("hat", "crown", 0).includeInDefaultPools().build());
    }
}
