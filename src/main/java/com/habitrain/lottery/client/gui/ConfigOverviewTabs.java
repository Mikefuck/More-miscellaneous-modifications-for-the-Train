package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.gui.config.ConfigConsoleLayout;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.skin.SkinContentBootstrap;
import com.habitrain.lottery.skin.SkinNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Extracted launcher buttons and overview summary renderers for the secondary console tabs
 * ({@code SKINS}, {@code CRATES}, {@code MAIL}, {@code DAILY}, {@code SELF_SELECT}) ({@code N-01}).
 */
final class ConfigOverviewTabs {
    private ConfigOverviewTabs() {
    }

    private static Component tr(String suffix, Object... args) {
        return Component.translatable("screen.habitrain_lottery.config." + suffix, args);
    }

    static void buildSkinsTab(LotteryConfigRootScreen host, Minecraft mc, int y) {
        host.addNavigationTabWidget(Button.builder(tr("skins.open_wardrobe"), b -> {
            if (ClientPlayNetworking.canSend(SkinNetwork.Request.TYPE)) {
                ClientPlayNetworking.send(new SkinNetwork.Request("", ""));
            } else if (mc != null) {
                mc.setScreen(new SkinWardrobeScreen(host));
            }
        }).bounds(host.pageLeft(), y, 120, 20).build());
    }

    static void buildCratesTab(LotteryConfigRootScreen host, Minecraft mc, int y) {
        int manageWidth = Math.min(240, host.pageWidth());
        host.addNavigationTabWidget(Button.builder(tr("action.crates_manage"), b -> {
            if (mc != null) {
                mc.setScreen(new CrateManageScreen(host));
            }
        }).bounds(host.pageLeft(), y, manageWidth, 20)
                .tooltip(Tooltip.create(tr("action.crates_manage_hint")))
                .build());
    }

    static void buildDailyTab(LotteryConfigRootScreen host, Minecraft mc, int y) {
        host.addNavigationTabWidget(Button.builder(
                Component.translatable("screen.habitrain_lottery.daily_admin.open"), b -> {
                    if (mc != null) {
                        mc.setScreen(new DailyTaskManageScreen(host));
                    }
                }).bounds(host.pageLeft(), y, Math.min(240, host.pageWidth()), 20)
                .tooltip(Tooltip.create(Component.translatable("screen.habitrain_lottery.daily_admin.open_hint")))
                .build());
        host.addNavigationTabWidget(Button.builder(
                Component.translatable("screen.habitrain_lottery.daily_shop_admin.open"), b -> {
                    if (mc != null) {
                        mc.setScreen(new DailyShopManageScreen(host));
                    }
                }).bounds(host.pageLeft(), y + 24, Math.min(240, host.pageWidth()), 20)
                .tooltip(Tooltip.create(Component.translatable("screen.habitrain_lottery.daily_shop_admin.open_hint")))
                .build());
    }

    static void buildMailTab(LotteryConfigRootScreen host, Minecraft mc, int y) {
        Button openMailBtn = host.addTabWidget(Button.builder(tr("section.mail"), b -> {
            if (mc != null) {
                mc.setScreen(new MailComposeScreen(host));
            }
        }).bounds(host.pageLeft(), y, 120, 20)
                .tooltip(Tooltip.create(tr("mail.open_hint")))
                .build());
        openMailBtn.active = host.editable();
    }

    static void buildSelfSelectTab(LotteryConfigRootScreen host, Minecraft mc, int y) {
        host.addNavigationTabWidget(Button.builder(
                Component.translatable("screen.habitrain_lottery.self_select.open"),
                b -> {
                    if (mc != null) {
                        mc.setScreen(new SelfSelectRoleConfigScreen(host));
                    }
                }).bounds(host.pageLeft(), y, Math.min(180, host.pageWidth()), 20).build());
    }

    static void renderOverview(
            GuiGraphics g,
            Font font,
            LotteryConfigRootScreen host,
            ConfigConsoleLayout layout,
            int selectedTab,
            int contentY
    ) {
        int left = host.pageLeft();
        int wrapW = Math.max(30, host.pageWidth() - 8);
        switch (selectedTab) {
            case LotteryConfigRootScreen.TAB_SKINS -> {
                int yy = contentY + 28;
                g.drawString(font, tr("skins.registered_count", SkinContentBootstrap.getRegisteredCount()),
                        left, yy, 0xFFFFFFFF, false);
                g.drawString(font, tr("skins.extension_hint"), left, yy + 14, 0xFF8A92A0, false);
                g.drawString(font, tr("skins.command_hint"), left, yy + 28, 0xFF8A92A0, false);
            }
            case LotteryConfigRootScreen.TAB_CRATES -> {
                var crates = CrateCatalog.publishedEntries();
                Component[] lines = {
                        CrateClientNetwork.connected()
                                ? Component.translatable("screen.habitrain_lottery.crate_manage.overview", crates.size(),
                                        crates.stream().filter(CrateCatalog.Entry::enabled).count())
                                : Component.translatable("screen.habitrain_lottery.crate_manage.overview_offline"),
                        Component.translatable("screen.habitrain_lottery.crate_manage.overview_hint"),
                        CrateClientNetwork.connected()
                                ? Component.translatable("screen.habitrain_lottery.crate_manage.source_server")
                                : Component.empty()
                };
                int yy = contentY + 30;
                for (Component line : lines) {
                    for (var wrapped : font.split(line, wrapW)) {
                        if (yy + font.lineHeight > layout.content().bottom()) break;
                        g.drawString(font, wrapped, left, yy, 0xFFB7C1C6, false);
                        yy += font.lineHeight + 3;
                    }
                    yy += 5;
                }
            }
            case LotteryConfigRootScreen.TAB_DAILY -> {
                int yy = contentY + 54;
                for (String key : new String[]{"overview_1", "overview_2", "overview_3", "overview_shop"}) {
                    for (var wrapped : font.split(
                            Component.translatable("screen.habitrain_lottery.daily_admin." + key), wrapW)) {
                        if (yy + font.lineHeight > layout.content().bottom()) break;
                        g.drawString(font, wrapped, left, yy, 0xFFB7C1C6, false);
                        yy += font.lineHeight + 3;
                    }
                    yy += 5;
                }
            }
            case LotteryConfigRootScreen.TAB_MAIL -> {
                int yy = contentY + 28;
                g.drawString(font, tr("mail.overview_1"), left, yy, 0xFFFFFFFF, false);
                g.drawString(font, tr("mail.overview_2"), left, yy + 14, 0xFF8A92A0, false);
            }
            default -> {
            }
        }
    }
}
