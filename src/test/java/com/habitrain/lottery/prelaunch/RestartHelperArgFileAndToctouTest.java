package com.habitrain.lottery.prelaunch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for Audit B-15 (@argfile launch & Win32 CreateProcessW 32k limit),
 * B-16 (ProcessHandle startInstant TOCTOU), I-25 (gameDir resolution), and N-10 (log rotation).
 */
class RestartHelperArgFileAndToctouTest {

    @TempDir
    Path root;

    @Test
    @Timeout(25)
    void argFileLaunchHandlesOver40kCommandLineSpecialCharsAndCleansUpTempFiles() throws Exception {
        String probeClasses = Path.of(ArgProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        StringBuilder hugeClasspath = new StringBuilder(probeClasses);
        int index = 0;
        while (hugeClasspath.length() < 45_000) {
            hugeClasspath.append(File.pathSeparator)
                    .append("C:/fake-modpack-cache/libraries/com/example/very-long-library-artifact-name-")
                    .append(index++)
                    .append("/1.0.0/very-long-library-artifact-name-")
                    .append(index)
                    .append(".jar");
        }

        Path marker = root.resolve("probe-output.bin");
        List<String> expectedArgs = List.of(
                marker.toString(),
                "",
                "quote\"backslash\\tab\tnewline\ncarriage\rform\fend",
                "--accessToken",
                "secret-token-value-not-on-process-cmdline",
                "plain-ascii-arg",
                "中文 参数 with spaces"
        );

        List<String> rawCommand = new ArrayList<>();
        rawCommand.add(javaExecutable());
        rawCommand.add("-Djava.awt.headless=true");
        rawCommand.add("-cp");
        rawCommand.add(hugeClasspath.toString());
        rawCommand.add(ArgProbe.class.getName());
        rawCommand.addAll(expectedArgs);

        // Verify @argfile builder collapses the >45k command into compact @argfile token(s).
        Path checkTempDir = root.resolve("argcheck");
        List<Path> checkCreated = new ArrayList<>();
        Charset charset = RestartHelper.resolveArgFileCharset();
        List<String> shortened = RestartHelper.buildLaunchCommandWithArgFiles(rawCommand, checkTempDir, charset, checkCreated);
        try {
            int shortenedLength = shortened.stream().mapToInt(String::length).sum();
            assertTrue(shortenedLength < 1_000, "Shortened command length was " + shortenedLength);
            assertFalse(checkCreated.isEmpty(), "Expected at least one @argfile to be created");
        } finally {
            for (Path p : checkCreated) {
                Files.deleteIfExists(p);
            }
        }

        // Execute full RestartHelper protocol in-process against a non-existent/exited PID.
        ByteArrayOutputStream planBytes = new ByteArrayOutputStream();
        try (DataOutputStream planOut = new DataOutputStream(planBytes)) {
            RestartHelper.writePlan(planOut, -999_999L, 0L, 5_000L, root, rawCommand);
            planOut.writeByte(1); // commit
        }

        ByteArrayOutputStream ackBytes = new ByteArrayOutputStream();
        int exitCode = RestartHelper.execute(new ByteArrayInputStream(planBytes.toByteArray()), ackBytes, false);
        assertEquals(0, exitCode, "RestartHelper.execute should succeed");
        try (DataInputStream ackIn = new DataInputStream(new ByteArrayInputStream(ackBytes.toByteArray()))) {
            assertTrue(ackIn.readBoolean());
        }

        assertTrue(Files.isRegularFile(marker), "Child JVM launched via @argfile must write marker file");
        List<String> receivedArgs = readCapturedArgs(marker);
        assertEquals(expectedArgs, receivedArgs);

        // Temporary habitrain-restart-*.args files must be cleaned up in finally.
        Path logsDir = root.resolve("logs");
        if (Files.isDirectory(logsDir)) {
            try (var files = Files.list(logsDir)) {
                List<Path> leftoverArgFiles = files
                        .filter(p -> p.getFileName().toString().endsWith(".args"))
                        .toList();
                assertEquals(List.of(), leftoverArgFiles, "Temporary @argfile containing tokens must be deleted");
            }
        }
    }

    @Test
    void waitForOldProcessSucceedsWhenOldProcessExitsInStartInstantToctouWindow() throws Exception {
        AtomicInteger aliveChecks = new AtomicInteger();
        ProcessHandle exitingRaceHandle = mockProcessHandle(
                () -> aliveChecks.incrementAndGet() == 1,
                Optional.empty()
        );
        // 1st isAlive() -> true, startInstant() -> empty, 2nd isAlive() -> false: must NOT throw!
        assertDoesNotThrow(() -> RestartHelper.waitForOldProcess(exitingRaceHandle, 1000L, 1000L));
        assertEquals(2, aliveChecks.get());

        // Genuine foreign/elevated process that stays alive without exposing startInstant: must throw IOException.
        ProcessHandle stillAliveUnknownHandle = mockProcessHandle(() -> true, Optional.empty());
        IOException ex = assertThrows(IOException.class,
                () -> RestartHelper.waitForOldProcess(stillAliveUnknownHandle, 1000L, 1000L));
        assertTrue(ex.getMessage().contains("Cannot identify"));
    }

    @Test
    void resolveGameDirUsesParentOfConfigDirAndNeverDefaultsToRepoWorkingDir() {
        Path gameDir = root.resolve("instance");
        Path configDir = gameDir.resolve("config");
        assertEquals(gameDir.toAbsolutePath().normalize(), GameRestart.resolveGameDir(configDir));

        Path customDir = root.resolve("standalone-dir");
        assertEquals(customDir.toAbsolutePath().normalize(), GameRestart.resolveGameDir(customDir));
    }

    @Test
    void rotateLogIfOversizedRotatesOverTwoMegabytesAndLeavesSmallLogsAlone() throws IOException {
        Path small = root.resolve("small.log");
        Files.writeString(small, "hello");
        RestartHelper.rotateLogIfOversized(small);
        assertTrue(Files.isRegularFile(small));
        assertFalse(Files.exists(root.resolve("small.log.1")));

        Path oversized = root.resolve("habitrain-restart.log");
        byte[] payload = new byte[(int) RestartHelper.MAX_LOG_BYTES + 64];
        payload[0] = 'A';
        payload[payload.length - 1] = 'Z';
        Files.write(oversized, payload);

        RestartHelper.rotateLogIfOversized(oversized);
        assertFalse(Files.exists(oversized));
        Path rotated = root.resolve("habitrain-restart.log.1");
        assertTrue(Files.isRegularFile(rotated));
        assertEquals(payload.length, Files.size(rotated));
    }

    private static ProcessHandle mockProcessHandle(java.util.function.BooleanSupplier isAliveSupplier,
                                                   Optional<java.time.Instant> startInstant) {
        ProcessHandle.Info info = (ProcessHandle.Info) Proxy.newProxyInstance(
                ProcessHandle.Info.class.getClassLoader(),
                new Class<?>[] {ProcessHandle.Info.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "startInstant" -> startInstant;
                    default -> Optional.empty();
                }
        );
        return (ProcessHandle) Proxy.newProxyInstance(
                ProcessHandle.class.getClassLoader(),
                new Class<?>[] {ProcessHandle.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isAlive" -> isAliveSupplier.getAsBoolean();
                    case "info" -> info;
                    case "pid" -> 424242L;
                    case "onExit" -> CompletableFuture.completedFuture(null);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "MockProcessHandle";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static List<String> readCapturedArgs(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int count = in.readInt();
            List<String> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                byte[] bytes = in.readNBytes(len);
                out.add(new String(bytes, StandardCharsets.UTF_8));
            }
            return out;
        }
    }

    public static final class ArgProbe {
        public static void main(String[] args) throws Exception {
            Path outFile = Path.of(args[0]);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outFile))) {
                out.writeInt(args.length);
                for (String arg : args) {
                    byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            }
        }
    }
}
