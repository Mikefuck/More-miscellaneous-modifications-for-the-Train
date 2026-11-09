package com.habitrain.lottery.prelaunch.late;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;

/** Non-blocking status window for connecting and downloading. */
final class ProgressWindow {

    private JFrame frame;
    private JLabel label;
    private JProgressBar bar;
    private JButton cancelButton;
    private Timer poller;
    private volatile boolean cancelled;
    private final List<Runnable> cancelActions = new CopyOnWriteArrayList<>();

    static ProgressWindow open(String status) {
        ProgressWindow window = new ProgressWindow();
        if (!GraphicsEnvironment.isHeadless()) {
            Ui.run(() -> window.create(status));
        }
        return window;
    }

    private void create(String status) {
        Ui.ensureLookAndFeel();
        frame = new JFrame(Ui.TITLE);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancel();
            }
        });
        label = new JLabel(status);
        label.setFont(Ui.BASE_FONT);
        bar = new JProgressBar(0, 100);
        bar.setIndeterminate(true);
        bar.setStringPainted(false);
        cancelButton = new JButton("取消更新，直接启动");
        cancelButton.setFont(Ui.BASE_FONT);
        cancelButton.addActionListener(event -> cancel());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(cancelButton);
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(label, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        frame.setContentPane(panel);
        frame.setSize(new Dimension(460, 165));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    boolean isCancelled() {
        return cancelled;
    }

    void onCancel(Runnable action) {
        if (action == null) {
            return;
        }
        cancelActions.add(action);
        if (cancelled) {
            runQuietly(action);
        }
    }

    void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        if (!GraphicsEnvironment.isHeadless()) {
            Ui.run(() -> {
                if (cancelButton != null) {
                    cancelButton.setEnabled(false);
                }
                if (label != null) {
                    label.setText("正在取消更新并继续启动当前整合包…");
                }
            });
        }
        for (Runnable action : cancelActions) {
            runQuietly(action);
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
        }
    }

    void status(String status) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Ui.run(() -> {
            if (frame != null && !cancelled) {
                stopPolling();
                label.setText(status);
                bar.setIndeterminate(true);
                bar.setStringPainted(false);
            }
        });
    }

    /** Follows AutoModpack's download manager, which the updater creates once downloading starts. */
    void follow(Supplier<DownloadManager> manager) {
        onCancel(() -> cancelDownloads(manager.get()));
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Ui.run(() -> {
            if (frame == null) {
                return;
            }
            stopPolling();
            poller = new Timer(250, e -> {
                DownloadManager current = manager.get();
                if (current == null) {
                    return;
                }
                if (cancelled) {
                    cancelDownloads(current);
                    return;
                }
                if (!current.isRunning()) {
                    label.setText("下载完成，正在安装并校验… 完成后将重启或自动退出");
                    bar.setIndeterminate(true);
                    bar.setStringPainted(false);
                    return;
                }
                int percent = Math.max(0, Math.min(100, current.getTotalPercentageOfFileSizeDownloaded()));
                long remainingMb = Math.max(0L, current.getTotalBytesRemaining()) / 1024L / 1024L;
                label.setText("正在下载整合包… 剩余约 " + remainingMb + " MB");
                bar.setIndeterminate(false);
                bar.setStringPainted(true);
                bar.setValue(percent);
            });
            poller.start();
        });
    }

    private static void cancelDownloads(DownloadManager manager) {
        if (manager == null) {
            return;
        }
        try {
            manager.cancelAllAndShutdown();
        } catch (NoSuchMethodError | RuntimeException fallback) {
            try {
                manager.getClass().getMethod("cancelAll").invoke(manager);
            } catch (Exception ignored) {
            }
        }
    }

    void hide() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Ui.run(() -> {
            if (frame != null) {
                frame.setVisible(false);
            }
        });
    }

    void show() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Ui.run(() -> {
            if (frame != null) {
                frame.setVisible(true);
            }
        });
    }

    void close() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Ui.run(() -> {
            stopPolling();
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        });
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
    }
}
