package com.habitrain.lottery.prelaunch;

import java.awt.GraphicsEnvironment;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.Timer;

/** Standalone JDK-only helper. Credentials travel through an anonymous pipe, never a launch script. */
public final class RestartHelper {
    private static final int MAGIC = 0x48414249;
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;
    static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;

    private RestartHelper() { }

    public static void main(String[] args) {
        int code = execute(System.in, System.out, true);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int execute(InputStream rawIn, OutputStream rawOut, boolean allowUi) {
        List<Path> createdArgFiles = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(rawIn);
             DataOutputStream output = new DataOutputStream(rawOut)) {
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
            if (input.read() != 1) return 0;
            waitForOldProcess(ProcessHandle.of(oldPid).orElse(null), oldStarted, timeout);
            Path log = directory.resolve("logs/habitrain-restarted-game.log");
            Files.createDirectories(log.getParent());
            rotateLogIfOversized(log);
            Charset argFileCharset = resolveArgFileCharset();
            Path argFileDir = effectiveArgFileDir(log.getParent(), argFileCharset.newEncoder());
            List<String> launchCommand = buildLaunchCommandWithArgFiles(command, argFileDir, argFileCharset, createdArgFiles);
            ProcessBuilder game = new ProcessBuilder(launchCommand).directory(directory.toFile())
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
            return 0;
        } catch (Throwable e) {
            if (e instanceof OutOfMemoryError || e instanceof InternalError || e instanceof UnknownError) {
                throw (Error) e;
            }
            // Do not include the command, arguments, or exception messages containing credentials.
            System.err.println("[HabiTrain] Automatic restart failed: " + e.getClass().getSimpleName()
                    + "; please start the game in your launcher.");
            if (allowUi && !GraphicsEnvironment.isHeadless()) {
                showFailure();
            }
            return 1;
        } finally {
            for (Path argFile : createdArgFiles) {
                try {
                    Files.deleteIfExists(argFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    static void waitForOldProcess(ProcessHandle old, long oldStarted, long timeout) throws Exception {
        if (old == null || !old.isAlive()) {
            return;
        }
        Instant startInstant = old.info().startInstant().orElse(null);
        if (startInstant == null) {
            if (!old.isAlive()) {
                return;
            }
            throw new IOException("Cannot identify the old game process");
        }
        if (startInstant.toEpochMilli() == oldStarted) {
            old.onExit().get(timeout, TimeUnit.MILLISECONDS);
        }
    }

    static void rotateLogIfOversized(Path log) {
        try {
            if (log != null && Files.isRegularFile(log) && Files.size(log) > MAX_LOG_BYTES) {
                Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Best-effort log rotation must never block restart.
        }
    }

    static Charset resolveArgFileCharset() {
        String jnu = System.getProperty("sun.jnu.encoding");
        if (jnu != null && !jnu.isBlank()) {
            try {
                return Charset.forName(jnu);
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Path effectiveArgFileDir(Path preferred, CharsetEncoder encoder) {
        Path normalized = preferred.toAbsolutePath().normalize();
        if (encoder.canEncode(normalized.toString())) {
            return normalized;
        }
        String systemTmp = System.getProperty("java.io.tmpdir");
        if (systemTmp != null && !systemTmp.isBlank()) {
            Path fallback = Path.of(systemTmp).toAbsolutePath().normalize();
            if (encoder.canEncode(fallback.toString())) {
                return fallback;
            }
        }
        return normalized;
    }

    static List<String> buildLaunchCommandWithArgFiles(List<String> command, Path tempDir,
                                                       Charset charset, List<Path> createdArgFiles) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("Missing launch command");
        }
        if (command.size() == 1) {
            return List.of(command.get(0));
        }
        Files.createDirectories(tempDir);
        CharsetEncoder encoder = charset.newEncoder();
        boolean pathEncodable = encoder.canEncode(tempDir.toAbsolutePath().normalize().toString());
        if (!pathEncodable) {
            return List.copyOf(command);
        }
        List<String> result = new ArrayList<>();
        result.add(command.get(0));
        List<String> pending = new ArrayList<>();
        for (int i = 1; i < command.size(); i++) {
            String argument = command.get(i);
            encoder.reset();
            if (encoder.canEncode(argument)) {
                pending.add(argument);
            } else {
                if (!pending.isEmpty()) {
                    Path argFile = writeArgFile(tempDir, pending, charset);
                    createdArgFiles.add(argFile);
                    result.add("@" + argFile.toAbsolutePath());
                    pending.clear();
                }
                result.add(argument);
            }
        }
        if (!pending.isEmpty()) {
            Path argFile = writeArgFile(tempDir, pending, charset);
            createdArgFiles.add(argFile);
            result.add("@" + argFile.toAbsolutePath());
        }
        return List.copyOf(result);
    }

    static Path writeArgFile(Path tempDir, List<String> arguments, Charset charset) throws IOException {
        Files.createDirectories(tempDir);
        Path argFile = Files.createTempFile(tempDir, "habitrain-restart-", ".args");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(argFile))) {
            for (String argument : arguments) {
                writeArgFileToken(out, argument, charset);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(argFile);
            } catch (IOException ignored) {
            }
            throw e;
        }
        return argFile;
    }

    static void writeArgFileToken(OutputStream out, String argument, Charset charset) throws IOException {
        byte[] raw = argument.getBytes(charset);
        out.write('"');
        for (byte b : raw) {
            int ch = b & 0xFF;
            if (ch == '\\') {
                out.write('\\');
                out.write('\\');
            } else if (ch == '"') {
                out.write('\\');
                out.write('"');
            } else if (ch == '\n') {
                out.write('\\');
                out.write('n');
            } else if (ch == '\r') {
                out.write('\\');
                out.write('r');
            } else if (ch == '\t') {
                out.write('\\');
                out.write('t');
            } else if (ch == '\f') {
                out.write('\\');
                out.write('f');
            } else {
                out.write(ch);
            }
        }
        out.write('"');
        out.write('\n');
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
