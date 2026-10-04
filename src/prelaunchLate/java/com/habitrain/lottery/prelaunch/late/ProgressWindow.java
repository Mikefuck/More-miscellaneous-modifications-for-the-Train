package com.habitrain.lottery.prelaunch.late;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
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
    private Timer poller;

    static ProgressWindow open(String status) {
        ProgressWindow window = new ProgressWindow();
        Ui.run(() -> window.create(status));
        return window;
    }

    private void create(String status) {
        Ui.ensureLookAndFeel();
        frame = new JFrame(Ui.TITLE);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        label = new JLabel(status);
        label.setFont(Ui.BASE_FONT);
        bar = new JProgressBar(0, 100);
        bar.setIndeterminate(true);
        bar.setStringPainted(false);
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(label, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        frame.setContentPane(panel);
        frame.setSize(new Dimension(460, 130));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    void status(String status) {
        Ui.run(() -> {
            if (frame != null) {
                stopPolling();
                label.setText(status);
                bar.setIndeterminate(true);
                bar.setStringPainted(false);
            }
        });
    }

    /** Follows AutoModpack's download manager, which the updater creates once downloading starts. */
    void follow(Supplier<DownloadManager> manager) {
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
                if (!current.isRunning()) {
                    label.setText("下载完成，正在安装整合包…（如需重启，AutoModpack 会弹出提示）");
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

    void hide() {
        Ui.run(() -> {
            if (frame != null) {
                frame.setVisible(false);
            }
        });
    }

    void show() {
        Ui.run(() -> {
            if (frame != null) {
                frame.setVisible(true);
            }
        });
    }

    void close() {
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
