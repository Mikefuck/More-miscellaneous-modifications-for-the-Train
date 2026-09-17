package com.habitrain.lottery.client.gui.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerAssetsViewStateTest {
    @Test
    void keepsSelectionSearchAndCardPageAcrossRebuilds() {
        PlayerAssetsViewState state = new PlayerAssetsViewState();
        state.setSearch("Mike");
        state.setSelectedUuid("11111111-1111-1111-1111-111111111111");
        state.setDetailPage(PlayerAssetsViewState.DetailPage.CARDS);
        state.setNarrowPage(PlayerAssetsViewState.NarrowPage.DETAIL);

        assertEquals("Mike", state.search());
        assertEquals("11111111-1111-1111-1111-111111111111", state.selectedUuid());
        assertEquals(PlayerAssetsViewState.DetailPage.CARDS, state.detailPage());
        assertEquals(PlayerAssetsViewState.NarrowPage.DETAIL, state.narrowPage());
    }

    @Test
    void keepsCardDraftsAndScrollForSamePlayerButResetsForAnotherPlayer() {
        PlayerAssetsViewState state = new PlayerAssetsViewState();
        state.beginCardPlayer("player-a");
        state.setCardScroll(56);
        state.setCardStepDraft("killer", "5");
        state.setCardSetDraft("killer", "12");

        state.beginCardPlayer("player-a");
        assertEquals(56, state.cardScroll());
        assertEquals("5", state.cardStepDraft("killer", "1"));
        assertEquals("12", state.cardSetDraft("killer", "0"));

        state.beginCardPlayer("player-b");
        assertEquals(0, state.cardScroll());
        assertEquals("1", state.cardStepDraft("killer", "1"));
        assertEquals("0", state.cardSetDraft("killer", "0"));
    }
}
