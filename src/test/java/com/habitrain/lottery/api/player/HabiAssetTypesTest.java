package com.habitrain.lottery.api.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HabiAssetTypesTest {

    @Test
    void cardKindParsesIdsAndEnumNames() {
        assertEquals(HabiCardKind.KILLER, HabiCardKind.parse("killer"));
        assertEquals(HabiCardKind.NEUTRAL_FOR_KILLER, HabiCardKind.parse("NEUTRAL_FOR_KILLER"));
        assertEquals(HabiCardKind.SELF_SELECT, HabiCardKind.parse(" self_select "));
        assertNull(HabiCardKind.parse("bogus"));
        assertNull(HabiCardKind.parse(null));
    }

    @Test
    void cardKindOrderingIsStable() {
        List<HabiCardKind> ordered = HabiCardKind.ordered();
        assertEquals(List.of("civilian", "neutral", "neutral_for_killer", "killer",
                "self_select", "limit_break"), ordered.stream().map(HabiCardKind::id).toList());
        assertEquals(4, ordered.stream().filter(HabiCardKind::isFactionCard).count());
        assertEquals(2, ordered.stream().filter(HabiCardKind::isVirtualCard).count());
    }

    @Test
    void operationParsesLeniently() {
        assertEquals(HabiAssetOperation.ADD, HabiAssetOperation.parse("add"));
        assertEquals(HabiAssetOperation.SET, HabiAssetOperation.parse(" Set "));
        assertNull(HabiAssetOperation.parse("multiply"));
        assertNull(HabiAssetOperation.parse(null));
    }

    @Test
    void assetResultFactories() {
        assertTrue(HabiAssetResult.success(5).ok());
        assertEquals(5, HabiAssetResult.success(5).newValue());
        assertEquals(HabiFailure.NONE, HabiAssetResult.success().failure());
        HabiAssetResult failure = HabiAssetResult.fail(HabiFailure.WRITE_FAILED, 3);
        assertTrue(failure.failed());
        assertEquals(3, failure.newValue());
        assertFalse(failure.message().isEmpty());
        assertEquals(HabiFailure.NONE, HabiAssetResult.success().failure());
    }

    @Test
    void playerAssetsDefensivelyCopiesAndSorts() {
        java.util.Map<String, Integer> cards = new java.util.LinkedHashMap<>();
        cards.put("killer", 2);
        java.util.Map<String, List<String>> skins = new java.util.LinkedHashMap<>();
        skins.put("knife", new java.util.ArrayList<>(List.of("a", "b")));
        java.util.Map<String, String> equipped = new java.util.LinkedHashMap<>();
        equipped.put("knife", "a");
        HabiPlayerAssets assets = new HabiPlayerAssets(java.util.UUID.randomUUID(), "n", false,
                10, 2, cards, 1, 0, skins, equipped, List.of("t"), "t", 3, 20000L);

        cards.put("killer", 99);
        skins.get("knife").clear();

        assertEquals(2, assets.card(HabiCardKind.KILLER));
        assertEquals(List.of("a", "b"), assets.skins("knife"));
        assertEquals("a", assets.equipped("knife"));
        assertEquals("default", assets.equipped("revolver"));
        assertThrows(UnsupportedOperationException.class,
                () -> assets.factionCards().put("killer", 1));
    }
}
