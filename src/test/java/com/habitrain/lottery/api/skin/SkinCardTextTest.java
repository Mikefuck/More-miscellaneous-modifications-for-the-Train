package com.habitrain.lottery.api.skin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkinCardTextTest {
    private static final String ID = "card_text_test";

    @AfterEach void cleanup() {
        for (String type : HabiSkinApi.types()) {
            SkinCardText.unregister(type, ID);
            HabiSkinApi.unregister(type, ID);
        }
    }

    @Test void declarationsCanPrecedeSkinsAndOnlyMatchTheExactTypeAndId() {
        assertTrue(SkinCardText.register(" Gun ", " CARD_TEXT_TEST ", "限定"));
        assertTrue(HabiSkinApi.find("revolver", ID).isEmpty());
        HabiSkinApi.register(SkinDefinition.builder("revolver", ID, 0).build());
        assertEquals("限定", SkinCardText.find("revolver", ID).orElseThrow().getString());
        assertTrue(SkinCardText.find("knife", ID).isEmpty());
        assertTrue(SkinCardText.find("revolver", "another_skin").isEmpty());
        assertTrue(SkinCardText.find("revolver", "default").isEmpty());
        assertTrue(SkinCardText.find("title", ID).isEmpty());
    }

    @Test void failedProviderRollsBackLabelsForOtherSkinsWithoutRemovingTheirDefinitions() {
        HabiSkinApi.register(SkinDefinition.builder("knife", ID, 0).build());
        SkinCardText.register("back", ID, "保留");
        String previous = HabiSkinApi.beginProvider("card_text_broken_provider");
        try {
            SkinCardText.register("knife", ID, "待回滚");
            SkinCardText.register("hat", ID, "先声明后注册");
        } finally { HabiSkinApi.endProvider(previous); }
        assertEquals(0, HabiSkinApi.removeProvider("card_text_broken_provider"));
        assertTrue(HabiSkinApi.find("knife", ID).isPresent());
        assertTrue(SkinCardText.find("knife", ID).isEmpty());
        assertTrue(SkinCardText.find("hat", ID).isEmpty());
        assertEquals("保留", SkinCardText.find("back", ID).orElseThrow().getString());
    }

    @Test void removingASkinAlsoRemovesItsOldLabel() {
        HabiSkinApi.register(SkinDefinition.builder("knife", ID, 0).build());
        SkinCardText.register("knife", ID, "旧标记");
        assertTrue(HabiSkinApi.unregister("knife", ID));
        HabiSkinApi.register(SkinDefinition.builder("knife", ID, 0).build());
        assertTrue(SkinCardText.find("knife", ID).isEmpty());
    }

    @Test void identicalReplayDoesNotStealOwnershipAndConflictsPreserveTheFirstDeclaration() {
        String previous = HabiSkinApi.beginProvider("card_text_first_provider");
        try { assertTrue(SkinCardText.register("knife", ID, "首个")); }
        finally { HabiSkinApi.endProvider(previous); }
        previous = HabiSkinApi.beginProvider("card_text_second_provider");
        try {
            assertFalse(SkinCardText.register("knife", ID, Component.literal("首个")));
            assertThrows(IllegalStateException.class, () -> SkinCardText.register("knife", ID, "冲突"));
        } finally { HabiSkinApi.endProvider(previous); }
        HabiSkinApi.removeProvider("card_text_second_provider");
        assertEquals("首个", SkinCardText.find("knife", ID).orElseThrow().getString());
        HabiSkinApi.removeProvider("card_text_first_provider");
        assertTrue(SkinCardText.find("knife", ID).isEmpty());
    }

    @Test void componentsPreserveTranslationsAndColorsWithoutSharingMutableChildren() {
        MutableComponent argument = Component.literal("活动").withColor(0x55FF55);
        MutableComponent suffix = Component.literal("限定").withColor(0xFFD700);
        MutableComponent input = Component.translatableWithFallback("test.card.text", "%s", argument)
                .append(suffix);
        Component expected = Component.translatableWithFallback("test.card.text", "%s",
                Component.literal("活动").withColor(0x55FF55))
                .append(Component.literal("限定").withColor(0xFFD700));
        SkinCardText.register("knife", ID, input);
        argument.append("被修改"); suffix.append("被修改"); input.append("被修改");
        Component found = SkinCardText.find("knife", ID).orElseThrow();
        assertEquals(expected, found);
        ((MutableComponent) found.getSiblings().getFirst()).append("被修改");
        var translation = (TranslatableContents) found.getContents();
        ((MutableComponent) translation.getArgs()[0]).append("被修改");
        assertEquals(expected, SkinCardText.find("knife", ID).orElseThrow());
    }

    @Test void removingOnlyTheLabelAllowsAReplacementAndLeavesTheSkinRegistered() {
        HabiSkinApi.register(SkinDefinition.builder("back", ID, 0).build());
        SkinCardText.register("back", ID, "旧文字");
        assertTrue(SkinCardText.unregister("back", ID));
        assertFalse(SkinCardText.unregister("back", ID));
        SkinCardText.register("back", ID, "新文字");
        assertTrue(HabiSkinApi.find("back", ID).isPresent());
        assertEquals("新文字", SkinCardText.find("back", ID).orElseThrow().getString());
    }
}
