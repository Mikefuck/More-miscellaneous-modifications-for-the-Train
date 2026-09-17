package com.habitrain.lottery.client.gui.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** State that must survive Screen widget-tree rebuilds on the player-assets page. */
public final class PlayerAssetsViewState {
    public enum DetailPage {
        BASE,
        CARDS
    }

    public enum NarrowPage {
        LIST,
        DETAIL
    }

    private String search = "";
    private String selectedUuid = "";
    private DetailPage detailPage = DetailPage.BASE;
    private NarrowPage narrowPage = NarrowPage.LIST;
    private String cardDraftPlayerUuid = "";
    private int cardScroll;
    private final Map<String, String> cardStepDrafts = new LinkedHashMap<>();
    private final Map<String, String> cardSetDrafts = new LinkedHashMap<>();

    public String search() {
        return search;
    }

    public void setSearch(String search) {
        this.search = search == null ? "" : search;
    }

    public String selectedUuid() {
        return selectedUuid;
    }

    public void setSelectedUuid(String selectedUuid) {
        this.selectedUuid = selectedUuid == null ? "" : selectedUuid;
    }

    public DetailPage detailPage() {
        return detailPage;
    }

    public void setDetailPage(DetailPage detailPage) {
        this.detailPage = detailPage == null ? DetailPage.BASE : detailPage;
    }

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
