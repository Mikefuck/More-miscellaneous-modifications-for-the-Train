package com.habitrain.lottery.client.gui.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State that must survive Screen widget-tree rebuilds on the player-assets page.
 *
 * <p>Only fields the page actually reads back live here. The screen object itself survives a
 * resize, so its own {@code playerSearchQuery} / {@code selectedPlayerUuid} /
 * {@code playerCardsPage} fields already persist across rebuilds; mirroring them here produced
 * two sources of truth that silently drifted apart. Search / selection / detail-tab state was
 * therefore removed from this class (and from its test).</p>
 */
public final class PlayerAssetsViewState {
    public enum NarrowPage {
        LIST,
        DETAIL
    }

    private NarrowPage narrowPage = NarrowPage.LIST;
    private String cardDraftPlayerUuid = "";
    private int cardScroll;
    private final Map<String, String> cardStepDrafts = new LinkedHashMap<>();
    private final Map<String, String> cardSetDrafts = new LinkedHashMap<>();

    public NarrowPage narrowPage() {
        return narrowPage;
    }

    public void setNarrowPage(NarrowPage narrowPage) {
        this.narrowPage = narrowPage == null ? NarrowPage.LIST : narrowPage;
    }

    public void beginCardPlayer(String uuid) {
        String normalized = uuid == null ? "" : uuid;
        if (normalized.equals(cardDraftPlayerUuid)) {
            return;
        }
        cardDraftPlayerUuid = normalized;
        cardScroll = 0;
        cardStepDrafts.clear();
        cardSetDrafts.clear();
    }

    public int cardScroll() {
        return cardScroll;
    }

    public void setCardScroll(int cardScroll) {
        this.cardScroll = Math.max(0, cardScroll);
    }

    public String cardStepDraft(String key, String fallback) {
        return cardStepDrafts.getOrDefault(key, fallback == null ? "" : fallback);
    }

    public void setCardStepDraft(String key, String value) {
        if (key != null && !key.isBlank()) {
            cardStepDrafts.put(key, value == null ? "" : value);
        }
    }

    public String cardSetDraft(String key, String fallback) {
        return cardSetDrafts.getOrDefault(key, fallback == null ? "" : fallback);
    }

    public void setCardSetDraft(String key, String value) {
        if (key != null && !key.isBlank()) {
            cardSetDrafts.put(key, value == null ? "" : value);
        }
    }
}
