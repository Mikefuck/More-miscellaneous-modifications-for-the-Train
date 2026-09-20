package com.habitrain.lottery.client.gui.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers exactly the state {@link PlayerAssetsViewState} owns.
 *
 * <p>It deliberately does <b>not</b> assert search / selected-uuid / detail-tab round-trips any
 * more: those fields existed only here, were written by the screen and never read back, and the
 * old test therefore proved nothing about the page's real behaviour.</p>
 */
class PlayerAssetsViewStateTest {
    @Test
    void keepsNarrowPageAcrossRebuilds() {
        PlayerAssetsViewState state = new PlayerAssetsViewState();
        assertEquals(PlayerAssetsViewState.NarrowPage.LIST, state.narrowPage());

        state.setNarrowPage(PlayerAssetsViewState.NarrowPage.DETAIL);
        assertEquals(PlayerAssetsViewState.NarrowPage.DETAIL, state.narrowPage());

        // null 归一化为 LIST，控件树重建时不会因为一次空赋值丢掉页面状态。
        state.setNarrowPage(null);
        assertEquals(PlayerAssetsViewState.NarrowPage.LIST, state.narrowPage());
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
