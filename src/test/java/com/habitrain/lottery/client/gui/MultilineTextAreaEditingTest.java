package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultilineTextAreaEditingTest {

    private static MultilineTextArea newEditor(int maxLength) {
        MultilineTextArea area = new MultilineTextArea(null, 0, 0, 240, 120);
        area.setMaxLength(maxLength);
        area.setFocused(true);
        return area;
    }

    private static void assertNoUnpairedSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)),
                        "Unpaired high surrogate at index " + i + " in: " + text);
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(c),
                        "Unpaired low surrogate at index " + i + " in: " + text);
            }
        }
    }

    @Test
    void insertAtDocumentHeadTruncatesInsertedStringWithoutChewingExistingFooter() {
        MultilineTextArea area = newEditor(20);
        area.setValue("Body\n--Footer");
        assertEquals(13, area.getValue().length());
        assertEquals(0, area.cursorLine());
        assertEquals(0, area.cursorCol());

        // Remaining budget is 7 chars; only the inserted prefix may be truncated, never "--Footer".
        area.insert("HEADER_OVERFLOW_TEXT");

        assertEquals("HEADER_Body\n--Footer", area.getValue());
        assertEquals(20, area.getValue().length());
        assertTrue(area.getValue().endsWith("\n--Footer"));

        // At capacity (budget == 0), further insertion is a clean no-op.
        area.insert("EXTRA");
        assertEquals("HEADER_Body\n--Footer", area.getValue());
    }

    @Test
    void insertInMiddleNearMaxLengthPreservesSuffix() {
        MultilineTextArea area = newEditor(16);
        area.setValue("HelloWorld");
        for (int i = 0; i < 5; i++) {
            area.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        }
        assertEquals(5, area.cursorCol());

        area.insert("123456789");

        assertEquals("Hello123456World", area.getValue());
        assertEquals(16, area.getValue().length());
    }

    @Test
    void emojiInsertionRespectsSurrogatePairBoundariesUnderTightBudget() {
        String gift = "\uD83C\uDF81"; // 🎁 (2 UTF-16 code units, 1 Unicode code point)

        // Budget == 1: cannot fit 2-char surrogate pair; must reject rather than leaving '\uD83C'.
        MultilineTextArea oddBudget = newEditor(5);
        oddBudget.setValue("ABCD");
        oddBudget.keyPressed(GLFW.GLFW_KEY_END, 0, 0);
        oddBudget.insert(gift);
        assertEquals("ABCD", oddBudget.getValue());
        assertNoUnpairedSurrogates(oddBudget.getValue());

        // Same budget == 1 via GLFW charTyped(high) + charTyped(low) sequence:
        oddBudget.charTyped('\uD83C', 0);
        oddBudget.charTyped('\uDF81', 0);
        assertEquals("ABCD", oddBudget.getValue());
        assertNoUnpairedSurrogates(oddBudget.getValue());

        // Budget == 2 with "X🎁Y": cut point falls on high surrogate of 🎁, so only "X" fits.
        MultilineTextArea cutMidPair = newEditor(6);
        cutMidPair.setValue("ABCD");
        cutMidPair.keyPressed(GLFW.GLFW_KEY_END, 0, 0);
        cutMidPair.insert("X" + gift + "Y");
        assertEquals("ABCDX", cutMidPair.getValue());
        assertNoUnpairedSurrogates(cutMidPair.getValue());

        // Budget == 3 with "X🎁Y": both "X" and full "🎁" fit cleanly.
        MultilineTextArea fitsPair = newEditor(7);
        fitsPair.setValue("ABCD");
        fitsPair.keyPressed(GLFW.GLFW_KEY_END, 0, 0);
        fitsPair.insert("X" + gift + "Y");
        assertEquals("ABCDX" + gift, fitsPair.getValue());
        assertNoUnpairedSurrogates(fitsPair.getValue());
    }

    @Test
    void cursorMovementBackspaceAndDeleteStepByWholeCodePoints() {
        String gift = "\uD83C\uDF81";
        MultilineTextArea area = newEditor(64);
        area.setValue("A" + gift + "B");
        assertEquals(0, area.cursorCol());

        // Step right over 'A' -> col 1, then over '🎁' -> col 3 (never stops at surrogate interior 2).
        area.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        assertEquals(1, area.cursorCol());
        area.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        assertEquals(3, area.cursorCol());

        // Step left back over '🎁' -> col 1.
        area.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0);
        assertEquals(1, area.cursorCol());

        // Delete forward at col 1 removes the entire emoji code point.
        area.keyPressed(GLFW.GLFW_KEY_DELETE, 0, 0);
        assertEquals("AB", area.getValue());
        assertEquals(1, area.cursorCol());
        assertNoUnpairedSurrogates(area.getValue());

        // Re-insert emoji via charTyped surrogate pair, then Backspace removes the entire emoji.
        area.charTyped('\uD83C', 0);
        area.charTyped('\uDF81', 0);
        assertEquals("A" + gift + "B", area.getValue());
        assertEquals(3, area.cursorCol());

        area.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
        assertEquals("AB", area.getValue());
        assertEquals(1, area.cursorCol());
        assertNoUnpairedSurrogates(area.getValue());
    }
}
