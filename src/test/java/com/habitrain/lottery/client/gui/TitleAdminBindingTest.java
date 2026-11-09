package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.title.TitleCatalog;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class TitleAdminBindingTest {
    @Test void initialBindingAndFocusedSelectionChangePreserveBothTemplates() throws Exception {
        Font font = new Font(id -> { throw new AssertionError("No rendering in this test"); }, false) {
            @Override public String plainSubstrByWidth(String text, int width) { return text; }
            @Override public int width(String text) { return text.length(); }
        };
        TitleAdminTab tab = new TitleAdminTab(null);
        EditBox id = new EditBox(font, 0, 0, 200, 20, Component.empty());
        EditBox display = new EditBox(font, 0, 0, 200, 20, Component.empty());
        set(tab, "titleIdBox", id);
        set(tab, "titleDisplayBox", display);
        TitleCatalog catalog = new TitleCatalog();
        var first = new TitleCatalog.TitleEntry("first", "First title", true);
        var second = new TitleCatalog.TitleEntry("second", "Second title", true);
        catalog.titles.add(first);
        catalog.titles.add(second);
        set(tab, "workingTitleCatalog", catalog);
        set(tab, "lastLoadedCatalogJson", "");
        id.setResponder(value -> tab.flushFieldsToModel());
        display.setResponder(value -> tab.flushFieldsToModel());
        var bind = TitleAdminTab.class.getDeclaredMethod("bindCatalogFields", TitleCatalog.TitleEntry.class);
        bind.setAccessible(true);
        bind.invoke(tab, first);
        assertEquals("First title", first.display);
        assertFalse(tab.isCatalogDirty());
        display.setFocused(true);
        display.setValue("Edited first");
        set(tab, "selectedTitleCatalogIndex", 1);
        bind.invoke(tab, second);
        assertEquals("second", id.getValue());
        assertEquals("Second title", display.getValue());
        assertEquals("Edited first", first.display);
        assertEquals("Second title", second.display);
        display.setValue("Edited second");
        assertEquals("Edited second", second.display);
    }

    private static void set(TitleAdminTab tab, String name, Object value) throws Exception {
        Field field = TitleAdminTab.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tab, value);
    }
}
