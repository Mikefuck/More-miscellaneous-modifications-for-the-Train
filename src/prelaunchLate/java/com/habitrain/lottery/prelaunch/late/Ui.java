package com.habitrain.lottery.prelaunch.late;

import java.awt.Dimension;
import java.awt.Font;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Small helpers for the blocking Swing dialogs shown from the (non-EDT) loader thread. */
final class Ui {

    static final Font BASE_FONT = new Font(Font.DIALOG, Font.PLAIN, 14);
    static final String TITLE = "哈比列车 · 启动前更新整合包";
    private static boolean lookAndFeelSet;

    private Ui() {
    }

    static <T> T call(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        return result.get();
    }

    static void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    static void ensureLookAndFeel() {
        if (lookAndFeelSet) {
            return;
        }
        lookAndFeelSet = true;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Default look and feel is fine.
        }
    }

    static void error(String message) {
        run(() -> {
            ensureLookAndFeel();
            JOptionPane.showMessageDialog(null, label(message), TITLE, JOptionPane.ERROR_MESSAGE);
        });
    }

    static boolean confirm(String message, String details, String yes, String no) {
        return call(() -> {
            ensureLookAndFeel();
            Object content = details == null ? label(message) : new Object[] {label(message), scroll(details)};
            int answer = JOptionPane.showOptionDialog(null, content, TITLE, JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, new Object[] {yes, no}, no);
            return answer == 0;
        });
    }

    static JLabel label(String text) {
        JLabel label = new JLabel("<html><div style='width:420px'>" + text + "</div></html>");
        label.setFont(BASE_FONT);
        return label;
    }

    private static JScrollPane scroll(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane pane = new JScrollPane(area);
        pane.setPreferredSize(new Dimension(520, 220));
        return pane;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
