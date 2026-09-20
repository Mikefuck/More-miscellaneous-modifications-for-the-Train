package com.habitrain.lottery.client;

import com.habitrain.lottery.api.skin.SkinItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.agmas.noellesroles.utils.lottery.LotteryManager;
import java.util.ArrayList;
import java.util.List;

/** Reward indices come from the lottery; previews use only the independent skin renderer. */
public final class PagedLootMultiScreen extends Screen {
    private final List<String> results = new ArrayList<>();
    private final Screen parent;
    private int page, pageSize;
    public PagedLootMultiScreen(int poolId, List<int[]> rows, Screen parent) {
        super(Component.literal("抽奖结果")); this.parent = parent;
        var pool = LotteryManager.getInstance().getLotteryPool(poolId);
        for (int[] row : rows) {
            if (pool == null || row == null || row.length < 2 || row[0] < 0 || row[0] >= pool.getQualityListGroupConfigs().size()) {
                results.add("未知奖励"); continue;
            }
            var band = pool.getQualityListGroupConfigs().get(row[0]).second;
            results.add(row[1] < 0 || row[1] >= band.size() ? "未知奖励" : band.get(row[1]));
        }
    }
    public String batchHeading() { return "抽奖结果 · " + results.size(); }
    @Override protected void init() {
        pageSize = Math.max(1, Math.min(5, (height - 95) / 36));
        page = Math.min(page, Math.max(0, (results.size() - 1) / pageSize));
        Button previous = addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { page--; rebuildWidgets(); })
                .bounds(width / 2 - 112, height - 30, 70, 20).build()); previous.active = page > 0;
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(width / 2 - 35, height - 30, 70, 20).build());
        Button next = addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { page++; rebuildWidgets(); })
                .bounds(width / 2 + 42, height - 30, 70, 20).build()); next.active = (page + 1) * pageSize < results.size();
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(font, batchHeading(), width / 2, 16, 0xFFFFFF);
        int left = Math.max(12, width / 2 - 180);
        for (int i = page * pageSize; i < Math.min(results.size(), (page + 1) * pageSize); i++) {
            String entry = results.get(i); int y = 42 + (i % pageSize) * 36;
            ItemStack preview = "coin".equals(entry) ? new ItemStack(Items.GOLD_NUGGET) : SkinItems.preview(entry);
            g.renderFakeItem(preview, left, y);
            g.drawString(font, font.plainSubstrByWidth("coin".equals(entry) ? "金币" : entry, width - left * 2 - 28), left + 26, y + 4, 0xFFFFFF);
        }
        g.drawCenteredString(font, (page + 1) + " / " + Math.max(1, (results.size() + pageSize - 1) / pageSize), width / 2, height - 46, 0xAAAAAA);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
