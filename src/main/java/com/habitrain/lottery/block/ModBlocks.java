package com.habitrain.lottery.block;

import com.habitrain.lottery.HabiLotteryMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class ModBlocks {
    public static final Block GACHA_TERMINAL = new GachaTerminalBlock();
    public static final Item GACHA_TERMINAL_ITEM = new BlockItem(GACHA_TERMINAL, new Item.Properties());

    public static final Block LOGIN_CALENDAR = new LoginCalendarBlock();
    public static final Item LOGIN_CALENDAR_ITEM = new BlockItem(LOGIN_CALENDAR, new Item.Properties());

    public static final Block MAILBOX = new MailboxBlock();
    public static final Item MAILBOX_ITEM = new BlockItem(MAILBOX, new Item.Properties());

    public static final Block DAILY_TASK_BOARD = new DailyTaskBoardBlock();
    public static final Item DAILY_TASK_BOARD_ITEM = new BlockItem(DAILY_TASK_BOARD, new Item.Properties());

    private ModBlocks() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, id("gacha_terminal"), GACHA_TERMINAL);
        Registry.register(BuiltInRegistries.ITEM, id("gacha_terminal"), GACHA_TERMINAL_ITEM);
        Registry.register(BuiltInRegistries.BLOCK, id("login_calendar"), LOGIN_CALENDAR);
        Registry.register(BuiltInRegistries.ITEM, id("login_calendar"), LOGIN_CALENDAR_ITEM);
        Registry.register(BuiltInRegistries.BLOCK, id("mailbox"), MAILBOX);
        Registry.register(BuiltInRegistries.ITEM, id("mailbox"), MAILBOX_ITEM);
        Registry.register(BuiltInRegistries.BLOCK, id("daily_task_board"), DAILY_TASK_BOARD);
        Registry.register(BuiltInRegistries.ITEM, id("daily_task_board"), DAILY_TASK_BOARD_ITEM);

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
            entries.accept(GACHA_TERMINAL_ITEM);
            entries.accept(LOGIN_CALENDAR_ITEM);
            entries.accept(MAILBOX_ITEM);
            entries.accept(DAILY_TASK_BOARD_ITEM);
        });
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.OP_BLOCKS).register(entries -> {
            entries.accept(GACHA_TERMINAL_ITEM);
            entries.accept(LOGIN_CALENDAR_ITEM);
            entries.accept(MAILBOX_ITEM);
            entries.accept(DAILY_TASK_BOARD_ITEM);
        });
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }
}
