package com.habitrain.lottery.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-line text area for pretty-printed JSON / backup viewing and editing.
 * Uses EditBox only for focus/clipboard patterns; rendering is custom.
 */
public class MultilineTextArea extends AbstractWidget {
    private final Font font;
    private String text = "";
    private final List<String> lines = new ArrayList<>();
    private int scroll;
    private int cursorLine;
    private int cursorCol;
    private boolean editable = true;
    private int maxLength = 1_000_000;

    public MultilineTextArea(Font font, int x, int y, int w, int h) {
        super(x, y, w, h, Component.literal("textarea"));
        this.font = font;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = Math.max(1, maxLength);
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
    }

    public boolean isEditable() {
        return editable;
    }

    public String getValue() {
        return text;
    }

    public void setValue(String value) {
        this.text = value == null ? "" : value;
        if (this.text.length() > maxLength) {
            this.text = this.text.substring(0, maxLength);
        }
        reflow();
        cursorLine = 0;
        cursorCol = 0;
        scroll = 0;
    }

    private void reflow() {
        lines.clear();
        if (text.isEmpty()) {
            lines.add("");
            return;
        }
        String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String r : raw) {
            lines.add(r);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        cursorLine = Mth.clamp(cursorLine, 0, lines.size() - 1);
        cursorCol = Mth.clamp(cursorCol, 0, lines.get(cursorLine).length());
        int visible = Math.max(1, (height - 8) / 10);
        scroll = Mth.clamp(scroll, 0, Math.max(0, lines.size() - visible));
    }

    private void rebuildTextFromLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        text = sb.toString();
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
            reflow();
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int bg = isFocused() ? 0xFF1A2030 : 0xFF141820;
        g.fill(getX(), getY(), getX() + width, getY() + height, bg);
        g.fill(getX(), getY(), getX() + width, getY() + 1, 0xFF57C6D6);
        g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, 0x3057C6D6);

        int lineH = 10;
        int visible = Math.max(1, (height - 8) / lineH);
        int y = getY() + 4;
        for (int i = 0; i < visible; i++) {
            int li = scroll + i;
            if (li >= lines.size()) {
                break;
            }
            String line = lines.get(li);
            int color = 0xFFE8ECF0;
            // simple JSON highlight
            if (line.trim().startsWith("\"")) {
                color = 0xFF9CDCFE;
            } else if (line.contains(":")) {
                color = 0xFFDCDCAA;
            } else if (line.trim().startsWith("{") || line.trim().startsWith("}")
                    || line.trim().startsWith("[") || line.trim().startsWith("]")) {
                color = 0xFFC586C0;
            }
            g.drawString(font, line, getX() + 4, y, color, false);
            if (isFocused() && editable && li == cursorLine) {
                int cx = getX() + 4 + font.width(line.substring(0, Math.min(cursorCol, line.length())));
                if ((System.currentTimeMillis() / 400) % 2 == 0) {
                    g.fill(cx, y - 1, cx + 1, y + 9, 0xFFFFFFFF);
                }
            }
            y += lineH;
        }

        // scrollbar
        if (lines.size() > visible) {
            int barH = Math.max(12, height * visible / lines.size());
            int maxScroll = Math.max(1, lines.size() - visible);
            int barY = getY() + (height - barH) * scroll / maxScroll;
            g.fill(getX() + width - 3, getY(), getX() + width, getY() + height, 0x30101820);
            g.fill(getX() + width - 3, barY, getX() + width, barY + barH, 0xA057C6D6);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isMouseOver(mouseX, mouseY)) {
            setFocused(true);
            int lineH = 10;
            int relY = (int) mouseY - getY() - 4;
            int li = scroll + Math.max(0, relY / lineH);
            li = Mth.clamp(li, 0, lines.size() - 1);
            cursorLine = li;
            String line = lines.get(li);
            int relX = (int) mouseX - getX() - 4;
            cursorCol = 0;
            for (int i = 0; i <= line.length(); i++) {
                if (font.width(line.substring(0, i)) >= relX) {
                    cursorCol = i;
                    break;
                }
                cursorCol = i;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int visible = Math.max(1, (height - 8) / 10);
        scroll = Mth.clamp(scroll - (int) Math.signum(vertical) * 3, 0, Math.max(0, lines.size() - visible));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused()) {
            return false;
        }
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                || (modifiers & GLFW.GLFW_MOD_SUPER) != 0;
        if (ctrl && keyCode == GLFW.GLFW_KEY_A) {
            // select-all not fully implemented; jump end
            cursorLine = lines.size() - 1;
            cursorCol = lines.get(cursorLine).length();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_V && editable) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip != null && !clip.isEmpty()) {
                insert(clip);
            }
            return true;
        }
        if (!editable) {
            // allow navigation
            return handleNav(keyCode);
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            backspace();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            deleteForward();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            insert("\n");
            return true;
        }
        return handleNav(keyCode);
    }

    private boolean handleNav(int keyCode) {
        int visible = Math.max(1, (height - 8) / 10);
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            if (cursorCol > 0) {
                cursorCol--;
            } else if (cursorLine > 0) {
                cursorLine--;
                cursorCol = lines.get(cursorLine).length();
            }
            ensureCursorVisible(visible);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            if (cursorCol < lines.get(cursorLine).length()) {
                cursorCol++;
            } else if (cursorLine < lines.size() - 1) {
                cursorLine++;
                cursorCol = 0;
            }
            ensureCursorVisible(visible);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            if (cursorLine > 0) {
                cursorLine--;
                cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
            }
            ensureCursorVisible(visible);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            if (cursorLine < lines.size() - 1) {
                cursorLine++;
                cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
            }
            ensureCursorVisible(visible);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            cursorCol = 0;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            cursorCol = lines.get(cursorLine).length();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            scroll = Math.max(0, scroll - visible);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            scroll = Math.min(Math.max(0, lines.size() - visible), scroll + visible);
            return true;
        }
        return false;
    }

    private void ensureCursorVisible(int visible) {
        if (cursorLine < scroll) {
            scroll = cursorLine;
        } else if (cursorLine >= scroll + visible) {
            scroll = cursorLine - visible + 1;
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!isFocused() || !editable) {
            return false;
        }
        if (codePoint == '\n' || codePoint == '\r') {
            return false;
        }
        if (Character.isISOControl(codePoint)) {
            return false;
        }
        insert(String.valueOf(codePoint));
        return true;
    }

    private void insert(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        String line = lines.get(cursorLine);
        String left = line.substring(0, cursorCol);
        String right = line.substring(cursorCol);
        String[] parts = (left + s + right).split("\n", -1);
        lines.remove(cursorLine);
        for (int i = 0; i < parts.length; i++) {
            lines.add(cursorLine + i, parts[i]);
        }
        cursorLine = cursorLine + parts.length - 1;
        cursorCol = parts[parts.length - 1].length() - right.length();
        if (cursorCol < 0) {
            cursorCol = parts[parts.length - 1].length();
        }
        rebuildTextFromLines();
        reflow();
        ensureCursorVisible(Math.max(1, (height - 8) / 10));
    }

    private void backspace() {
        if (cursorCol > 0) {
            String line = lines.get(cursorLine);
            lines.set(cursorLine, line.substring(0, cursorCol - 1) + line.substring(cursorCol));
            cursorCol--;
        } else if (cursorLine > 0) {
            String prev = lines.get(cursorLine - 1);
            String cur = lines.get(cursorLine);
            cursorCol = prev.length();
            lines.set(cursorLine - 1, prev + cur);
            lines.remove(cursorLine);
            cursorLine--;
        } else {
            return;
        }
        rebuildTextFromLines();
        reflow();
    }

    private void deleteForward() {
        String line = lines.get(cursorLine);
        if (cursorCol < line.length()) {
            lines.set(cursorLine, line.substring(0, cursorCol) + line.substring(cursorCol + 1));
        } else if (cursorLine < lines.size() - 1) {
            lines.set(cursorLine, line + lines.get(cursorLine + 1));
            lines.remove(cursorLine + 1);
        } else {
            return;
        }
        rebuildTextFromLines();
        reflow();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
