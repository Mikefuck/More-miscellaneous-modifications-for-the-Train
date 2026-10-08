package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.Font;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.*;

class MultilineTextAreaTest {
    private MultilineTextArea emptyArea() {
        Font font = new Font(id -> {
            throw new AssertionError("An empty line must not request glyphs");
        }, false);
        return new MultilineTextArea(font, 20, 30, 200, 80);
    }

    @Test
    void freshEmptyRecipientFieldAcceptsFirstClickAndTyping() {
        MultilineTextArea area = emptyArea();

        assertTrue(area.mouseClicked(24, 34, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertTrue(area.isFocused());
        assertTrue(area.charTyped('M', 0));
        assertTrue(area.charTyped('i', 0));
        assertEquals("Mi", area.getValue());
    }

    @Test
    void freshEmptyFieldAcceptsKeyboardFocusAndEditing() {
        MultilineTextArea area = emptyArea();
        area.setFocused(true);

        for (int key : new int[]{GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT,
                GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_HOME,
                GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE}) {
            assertTrue(area.keyPressed(key, 0, 0));
        }
        assertTrue(area.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL));
        assertTrue(area.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        assertTrue(area.charTyped('M', 0));
        assertEquals("\nM", area.getValue());
        assertTrue(area.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0));
        assertTrue(area.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0));
        assertEquals("", area.getValue());
        assertTrue(area.charTyped('B', 0));
        assertEquals("B", area.getValue());
    }
}
