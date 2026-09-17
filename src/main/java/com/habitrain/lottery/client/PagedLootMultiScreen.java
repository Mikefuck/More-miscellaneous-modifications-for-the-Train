package com.habitrain.lottery.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.agmas.noellesroles.client.screen.LootMultiScreen;

import java.util.List;

/** Keeps the upstream reveal animation readable, with five results per page. */
public final class PagedLootMultiScreen extends LootMultiScreen {
    private static final int PAGE_SIZE = 5;
    private final int poolId;
    private final List<int[]> allResults;
    private final Screen returnScreen;
    private final int page;

    public PagedLootMultiScreen(int poolId, List<int[]> results, Screen parent) {
        this(poolId, results, parent, 0);
    }

    private PagedLootMultiScreen(int poolId, List<int[]> results, Screen parent, int page) {
        super(poolId, results.subList(page * PAGE_SIZE, Math.min(results.size(), (page + 1) * PAGE_SIZE)), parent);
        this.poolId = poolId;
        this.allResults = results;
        this.returnScreen = parent;
        this.page = page;
    }

    public String batchHeading() {
        int first = page * PAGE_SIZE + 1;
        int last = Math.min(allResults.size(), (page + 1) * PAGE_SIZE);
        return Component.translatable("screen.habitrain_lottery.loot.results", allResults.size(), first, last).getString();
    }

    @Override
    protected void init() {
        super.init();
        int pages = (allResults.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        Button previous = addRenderableWidget(Button.builder(
                Component.translatable("screen.habitrain_lottery.loot.previous"), b -> openPage(page - 1))
                .bounds(width / 2 - 104, height - 30, 72, 20).build());
        previous.active = page > 0;
        Button pageIndicator = addRenderableWidget(Button.builder(Component.literal((page + 1) + " / " + pages), b -> {})
                .bounds(width / 2 - 28, height - 30, 56, 20).build());
        pageIndicator.active = false;
        Button next = addRenderableWidget(Button.builder(
                Component.translatable("screen.habitrain_lottery.loot.next"), b -> openPage(page + 1))
                .bounds(width / 2 + 32, height - 30, 72, 20).build());
        next.active = page + 1 < pages;
    }

    private void openPage(int targetPage) {
        if (minecraft != null) minecraft.setScreen(new PagedLootMultiScreen(poolId, allResults, returnScreen, targetPage));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(returnScreen);
    }
}
