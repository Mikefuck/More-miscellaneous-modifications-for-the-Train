package com.habitrain.lottery.prelaunch;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import javax.swing.JDialog;
import javax.swing.Timer;

/** Standalone JDK-only helper. Credentials travel through an anonymous pipe, never a launch script. */
public final class RestartHelper {
    private static final int MAGIC = 0x48414249;
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;

    private RestartHelper() { }

    public static void main(String[] args) {
        try (DataInputStream input = new DataInputStream(System.in);
             DataOutputStream output = new DataOutputStream(System.out)) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid restart protocol");
            long oldPid = input.readLong();
            long oldStarted = input.readLong();
            long timeout = input.readLong();
            if (timeout < 1 || timeout > 120_000) throw new IOException("Invalid exit timeout");
            Path directory = Path.of(readString(input));
            int size = input.readInt();
            if (size < 1 || size > 16_384) throw new IOException("Invalid launch argument count");
            List<String> command = new ArrayList<>(size);
            for (int i = 0; i < size; i++) command.add(readString(input));
            output.writeBoolean(true);
            output.flush();
            // No committed request (e.g. parent failed to save its cooldown) means no restart.
            if (input.read() != 1) return;
            ProcessHandle old = ProcessHandle.of(oldPid).orElse(null);
            if (old != null && old.isAlive()) {
                long actualStarted = old.info().startInstant().map(Instant::toEpochMilli)
                        .orElseThrow(() -> new IOException("Cannot identify the old game process"));
                if (actualStarted == oldStarted) old.onExit().get(timeout, TimeUnit.MILLISECONDS);
            }
            Path log = directory.resolve("logs/habitrain-restarted-game.log");
            Files.createDirectories(log.getParent());
            ProcessBuilder game = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.PIPE);
            clearInjectedOptions(game);
            Process started = game.start();
            started.getOutputStream().close();
            // ProcessBuilder.start() also succeeds when Java immediately fails to find the
            // launcher's deleted libraries/natives or cannot initialise an authentication agent.
            if (started.waitFor(10, TimeUnit.SECONDS) && started.exitValue() != 0) {
                throw new IOException("Restarted game exited during startup");
            }
            System.err.println("[HabiTrain] Restarted game process " + started.pid());
        } catch (Exception e) {
            // Do not include the command, arguments, or exception messages containing credentials.
            System.err.println("[HabiTrain] Automatic restart failed: " + e.getClass().getSimpleName()
                    + "; please start the game in your launcher.");
            if (!GraphicsEnvironment.isHeadless()) {
                showFailure();
            }
        }
    }

    private static void showFailure() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                JOptionPane pane = new JOptionPane(
                        "更新已安装，但游戏未能自动启动。请回到你使用的启动器重新启动游戏。\n"
                                + "此提示将在 5 秒后关闭。详情见 logs/habitrain-restart.log\n"
                                + "和 logs/habitrain-restarted-game.log。", JOptionPane.ERROR_MESSAGE);
                JDialog dialog = pane.createDialog(null, "哈比列车 · 自动重启");
                Timer timer = new Timer(5_000, event -> dialog.dispose());
                timer.setRepeats(false);
                timer.start();
                try { dialog.setVisible(true); }
                finally { timer.stop(); dialog.dispose(); }
            });
        } catch (Exception | LinkageError ignored) {
            // The parent already exited normally; a missing GUI must not strand this helper.
        }
    }

    static void writePlan(DataOutputStream output, long oldPid, long oldStarted, long timeout,
                          Path directory, List<String> command) throws IOException {
        output.writeInt(MAGIC);
        output.writeLong(oldPid);
        output.writeLong(oldStarted);
        output.writeLong(timeout);
        writeString(output, directory.toString());
        output.writeInt(command.size());
        for (String argument : command) writeString(output, argument);
        output.flush();
    }

    static void clearInjectedOptions(ProcessBuilder process) {
        // These options are already present in the captured VM arguments. They must not affect
        // the helper or be applied a second time to the new game (especially -javaagent).
        process.environment().remove("JAVA_TOOL_OPTIONS");
        process.environment().remove("JDK_JAVA_OPTIONS");
        process.environment().remove("_JAVA_OPTIONS");
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Launch argument too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_STRING_BYTES) throw new IOException("Invalid launch argument size");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new IOException("Incomplete launch argument");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
