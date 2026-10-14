package com.habitrain.lottery.prelaunch.late;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/** Blocking server picker window shown before the game starts. */
final class PickerDialog {

    enum Action { UPDATE, SKIP, DEFAULT }

    record Choice(Action action, PickerOption option, boolean disableNextTime) {
    }

    private PickerDialog() {
    }

    static Choice show(List<PickerOption> options, boolean updateOnLaunch, int autoContinueSeconds) {
        AtomicReference<Window> window = new AtomicReference<>();
        return awaitChoice(done -> {
            Window created = new Window(options, updateOnLaunch, autoContinueSeconds, done);
            window.set(created);
            created.open();
        }, () -> {
            Window created = window.get();
            if (created != null) {
                try { created.stopCountdown(); } finally { created.frame.dispose(); }
            }
        });
    }

    /** Injectable EDT initialization lets failures and cancellation be tested without a desktop. */
    static Choice awaitChoice(Consumer<Consumer<Choice>> open, Runnable close) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Picker cannot block the EDT");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Choice> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        SwingUtilities.invokeLater(() -> {
            if (cancelled.get()) { latch.countDown(); return; }
            try {
                open.accept(choice -> {
                    if (result.compareAndSet(null, choice)) latch.countDown();
                });
            } catch (Throwable error) {
                failure.set(error);
                try { close.run(); } finally { latch.countDown(); }
            }
        });
        try {
            latch.await();
        } catch (InterruptedException error) {
            cancelled.set(true);
            SwingUtilities.invokeLater(close);
            Thread.currentThread().interrupt();
            return new Choice(Action.DEFAULT, null, false);
        }
        if (failure.get() != null) throw new IllegalStateException("Picker window initialization failed", failure.get());
        return result.get();
    }

    private static final class Window {

        private final List<PickerOption> options;
        private final boolean updateOnLaunch;
        private final Consumer<Choice> onChoice;
        private final JFrame frame = new JFrame(Ui.TITLE);
        private final JList<PickerOption> list;
        private final JButton updateButton = new JButton("下载/更新所选服务器的整合包");
        private final JButton skipButton = new JButton("跳过更新，直接启动");
        private final JCheckBox disableBox = new JCheckBox("以后不再显示此窗口（可在 config/habitrain_prelaunch.json 恢复）");
        private final JLabel countdownLabel = new JLabel(" ");
        private Timer countdown;
        private AWTEventListener interactionListener;
        private int remaining;
        private boolean done;

        Window(List<PickerOption> options, boolean updateOnLaunch, int autoContinueSeconds, Consumer<Choice> onChoice) {
            this.options = options;
            this.updateOnLaunch = updateOnLaunch;
            this.onChoice = onChoice;
            this.remaining = autoContinueSeconds;
            this.list = new JList<>(options.toArray(new PickerOption[0]));
        }

        void open() {
            Ui.ensureLookAndFeel();
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    finish(Action.SKIP, null);
                }
            });

            JLabel header = new JLabel("<html><b>选择要在启动游戏前下载/更新整合包的服务器</b><br>"
                    + "列表来自多人游戏服务器列表。没进过的服务器会先用当前账号登录一次服务器以获取下载凭证（不会进入游戏）。</html>");
            header.setFont(Ui.BASE_FONT);
            header.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new OptionRenderer());
            list.addListSelectionListener(e -> updateButton.setEnabled(list.getSelectedValue() != null));
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                        finish(Action.UPDATE, list.getSelectedValue());
                    }
                }
            });
            selectDefault();
            JScrollPane scroll = new JScrollPane(list);
            scroll.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(0, 12, 0, 12), scroll.getBorder()));

            updateButton.setFont(Ui.BASE_FONT);
            skipButton.setFont(Ui.BASE_FONT);
            disableBox.setFont(Ui.BASE_FONT.deriveFont(12f));
            countdownLabel.setFont(Ui.BASE_FONT.deriveFont(12f));
            countdownLabel.setForeground(new Color(0x8A6D00));
            updateButton.addActionListener(e -> {
                if (list.getSelectedValue() != null) {
                    finish(Action.UPDATE, list.getSelectedValue());
                }
            });
            skipButton.addActionListener(e -> finish(Action.SKIP, null));
            updateButton.setEnabled(list.getSelectedValue() != null);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(skipButton);
            buttons.add(updateButton);
            JPanel south = new JPanel(new BorderLayout(0, 6));
            south.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
            south.add(countdownLabel, BorderLayout.NORTH);
            south.add(disableBox, BorderLayout.CENTER);
            south.add(buttons, BorderLayout.SOUTH);

            frame.getContentPane().setLayout(new BorderLayout());
            frame.getContentPane().add(header, BorderLayout.NORTH);
            frame.getContentPane().add(scroll, BorderLayout.CENTER);
            frame.getContentPane().add(south, BorderLayout.SOUTH);
            frame.setMinimumSize(new Dimension(600, 400));
            frame.setSize(680, 460);
            frame.setLocationRelativeTo(null);
            frame.getRootPane().setDefaultButton(updateButton);
            frame.setVisible(true);
            frame.setAlwaysOnTop(true);
            frame.toFront();
            frame.requestFocus();
            frame.setAlwaysOnTop(false);

            startCountdown();
        }

        private void selectDefault() {
            int index = -1;
            for (int i = 0; i < options.size() && index < 0; i++) {
                if (options.get(i).selected()) {
                    index = i;
                }
            }
            if (index < 0 && !options.isEmpty()) {
                index = 0;
            }
            if (index >= 0) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
            }
        }

        private void startCountdown() {
            if (remaining <= 0) {
                return;
            }
            updateCountdownText();
            countdown = new Timer(1000, e -> {
                remaining--;
                if (remaining <= 0) {
                    finish(Action.DEFAULT, null);
                } else {
                    updateCountdownText();
                }
            });
            countdown.start();
            // Any click or key press means the player is here; stop the auto-continue.
            interactionListener = event -> {
                if (event.getID() == MouseEvent.MOUSE_PRESSED || event.getID() == KeyEvent.KEY_PRESSED) {
                    stopCountdown();
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(interactionListener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        }

        private void updateCountdownText() {
            String then = updateOnLaunch ? "按 AutoModpack 默认设置启动（检查当前整合包）" : "直接启动";
            countdownLabel.setText(remaining + " 秒后" + then + "（点击窗口任意处取消倒计时）");
        }

        private void stopCountdown() {
            if (countdown != null) {
                countdown.stop();
                countdown = null;
                countdownLabel.setText(" ");
            }
            if (interactionListener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(interactionListener);
                interactionListener = null;
            }
        }

        private void finish(Action action, PickerOption option) {
            if (done) {
                return;
            }
            done = true;
            stopCountdown();
            boolean disable = disableBox.isSelected();
            frame.dispose();
            onChoice.accept(new Choice(action, option, disable));
        }
    }

    private static final class OptionRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            PickerOption option = (PickerOption) value;
            super.getListCellRendererComponent(list, describe(option), index, isSelected, cellHasFocus);
            setFont(Ui.BASE_FONT);
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            return this;
        }

        private static String describe(PickerOption option) {
            String status = switch (option.state(System.currentTimeMillis() / 1000L)) {
                case READY -> "<font color='#1B7F3B'>已安装整合包「" + Ui.escape(option.installedName())
                        + "」，将检查并下载更新</font>";
                case NEEDS_LOGIN -> "<font color='#8A6D00'>已安装整合包「" + Ui.escape(option.installedName())
                        + "」，下载凭证缺失或已过期，将重新登录服务器获取</font>";
                case NOT_INSTALLED -> "<font color='#1F5FA8'>未安装：将登录服务器获取凭证，然后下载整合包</font>";
            };
            String current = option.selected() ? " <b>[当前整合包]</b>" : "";
            return "<html><b>" + Ui.escape(option.title()) + "</b>" + current
                    + " &nbsp;<font color='#777777'>" + Ui.escape(option.address()) + "</font><br>" + status + "</html>";
        }
    }
}
