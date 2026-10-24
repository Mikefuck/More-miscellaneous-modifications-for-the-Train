package com.habitrain.lottery.prelaunch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GameRestartTest {
    private static final String MAIN = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    @TempDir Path root;

    @Test void preservesUnicodeSpacesAndEmptyArgumentsWithoutShellQuoting() throws Exception {
        List<String> command = GameRestart.buildCommand("C:/Java 21/bin/javaw.exe",
                List.of("-Xmx4G", "-Dtest=中文 空格"), "C:/游戏 目录/a.jar;C:/b.jar", MAIN + " --username Mike",
                new String[] {"--username", "Mike 中文", "--accessToken", "sample-token", "--empty", ""});
        assertEquals(List.of("C:/Java 21/bin/javaw.exe", "-Xmx4G", "-Dtest=中文 空格", "-cp",
                "C:/游戏 目录/a.jar;C:/b.jar", MAIN, "--username", "Mike 中文", "--accessToken", "sample-token",
                "--empty", ""), command);
    }

    @Test void refusesUnknownLaunchWrappersAndDebuggerPorts() {
        assertThrows(Exception.class, () -> GameRestart.buildCommand("java", List.of(), "libs", "some.Wrapper", new String[0]));
        assertThrows(Exception.class, () -> GameRestart.buildCommand("java", List.of("-agentlib:jdwp=address=5005"),
                "libs", MAIN, new String[0]));
    }

    @Test void launcherWrappersFallBackWhileBothStandardFabricEntrypointsAreAccepted() throws Exception {
        for (String entrypoint : List.of(MAIN, "net.fabricmc.loader.launch.knot.KnotClient")) {
            assertTrue(GameRestart.buildCommand("java", List.of(), "libs", entrypoint,
                    new String[] {"--gameDir", "游戏 目录"}).contains(entrypoint));
        }
        for (String wrapper : List.of("org.prismlauncher.EntryPoint", "org.multimc.EntryPoint", "launcher.Wrapper")) {
            assertThrows(java.io.IOException.class, () -> GameRestart.buildCommand("java", List.of(), "libs",
                    wrapper, new String[0]));
        }
        assertThrows(java.io.IOException.class, () -> GameRestart.buildCommand("java", List.of(), "", MAIN, new String[0]));
    }

    @Test @Timeout(15)
    void helperDetectsImmediateJavaStartupFailureWithoutRetryingOrLoggingCredentials() throws Exception {
        String classes = Path.of(RestartHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process helper = new ProcessBuilder(java(), "-Djava.awt.headless=true", "-cp", classes, RestartHelper.class.getName())
                .directory(root.toFile()).start();
        try {
            Process exited = probe("fail");
            assertTrue(exited.waitFor(5, TimeUnit.SECONDS));
            try (DataOutputStream plan = new DataOutputStream(helper.getOutputStream())) {
                RestartHelper.writePlan(plan, exited.pid(), 0, 1_000, root, probeCommand("fail", "sample-secret-token"));
                assertTrue(new DataInputStream(helper.getInputStream()).readBoolean());
                plan.writeByte(1);
            }
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS));
            String errors = new String(helper.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(errors.contains("Automatic restart failed"));
            assertFalse(errors.contains("sample-secret-token"));
            assertFalse(errors.contains("Restarted game process"));
        } finally {
            stop(helper);
        }
    }

    @Test @Timeout(20)
    void preparesTheStandalonePackagedHelperAndCancelsWithoutLaunchingTheGame() throws Exception {
        String original = System.getProperty("sun.java.command");
        try {
            System.setProperty("sun.java.command", MAIN);
            GameRestart restart = GameRestart.capture(root, new String[] {"--username", "test"});
            try (JarFile jar = new JarFile(root.resolve("habitrain-restart-helper.jar").toFile())) {
                assertEquals(1, jar.size());
                assertNotNull(jar.getJarEntry(RestartHelper.class.getName().replace('.', '/') + ".class"));
            }
            try (GameRestart.Prepared prepared = restart.prepare()) {
                assertNotNull(prepared); // the real javaw helper acknowledged the complete plan
            }
        } finally {
            if (original == null) System.clearProperty("sun.java.command");
            else System.setProperty("sun.java.command", original);
        }
    }

    @Test @Timeout(25)
    void helperWaitsForOldJvmThenStartsExactlyOneNewProcess() throws Exception {
        Path marker = root.resolve("重启成功 marker.txt");
        Process old = probe("wait");
        Process helper = null;
        try {
            assertEquals("READY", new BufferedReader(new InputStreamReader(old.getInputStream())).readLine());
            helper = helper();
            try (DataOutputStream plan = new DataOutputStream(helper.getOutputStream())) {
                RestartHelper.writePlan(plan, old.pid(), old.info().startInstant().orElseThrow().toEpochMilli(),
                        10_000, root, probeCommand("write", marker.toString()));
                assertTrue(new DataInputStream(helper.getInputStream()).readBoolean());
                plan.writeByte(1);
                plan.flush();
                Thread.sleep(150);
                assertTrue(old.isAlive());
                assertFalse(Files.exists(marker), "The new process must not start while the old game is alive");
                old.getOutputStream().close();
                assertTrue(old.waitFor(10, TimeUnit.SECONDS));
                assertTrue(helper.waitFor(10, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(20);
                assertEquals("中文 参数 with spaces", Files.readString(marker));
            }
        } finally {
            stop(helper);
            stop(old);
        }
    }

    @Test @Timeout(15)
    void helperDoesNotRestartAnUncommittedRequest() throws Exception {
        Path marker = root.resolve("aborted.txt");
        Process helper = helper();
        try {
            try (DataOutputStream plan = new DataOutputStream(helper.getOutputStream())) {
                RestartHelper.writePlan(plan, ProcessHandle.current().pid(),
                        ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),
                        1_000, root, probeCommand("write", marker.toString()));
                assertTrue(new DataInputStream(helper.getInputStream()).readBoolean());
            }
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS));
            assertFalse(Files.exists(marker));
        } finally {
            stop(helper);
        }
    }

    @Test @Timeout(15)
    void exitTimeoutDoesNotStartAnotherGameAlongsideTheOldOne() throws Exception {
        Path marker = root.resolve("timeout.txt");
        Process helper = helper();
        try {
            try (DataOutputStream plan = new DataOutputStream(helper.getOutputStream())) {
                RestartHelper.writePlan(plan, ProcessHandle.current().pid(),
                        ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),
                        100, root, probeCommand("write", marker.toString()));
                assertTrue(new DataInputStream(helper.getInputStream()).readBoolean());
                plan.writeByte(1);
            }
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS));
            assertFalse(Files.exists(marker));
        } finally {
            stop(helper);
        }
    }

    private Process helper() throws Exception {
        String classes = Path.of(RestartHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new ProcessBuilder(java(), "-Djava.awt.headless=true", "-cp", classes, RestartHelper.class.getName())
                .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private Process probe(String mode) throws Exception {
        return new ProcessBuilder(probeCommand(mode)).directory(root.toFile()).start();
    }

    private static List<String> probeCommand(String mode, String... extra) throws Exception {
        String classes = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var command = new java.util.ArrayList<>(List.of(java(), "-cp", classes, Probe.class.getName(), mode));
        command.addAll(List.of(extra));
        command.add("中文 参数 with spaces");
        return command;
    }

    private static String java() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static void stop(Process process) throws Exception {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(3, TimeUnit.SECONDS));
            }
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("fail")) {
                System.exit(7);
            } else if (args[0].equals("wait")) {
                System.out.println("READY");
                System.out.flush();
                System.in.read();
            } else {
                Files.writeString(Path.of(args[1]), args[2]);
            }
        }
    }
}
