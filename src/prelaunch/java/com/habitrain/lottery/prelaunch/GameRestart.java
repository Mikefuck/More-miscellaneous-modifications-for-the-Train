package com.habitrain.lottery.prelaunch;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Captures a standard Fabric Java launch and arms a detached helper before exiting this JVM. */
public final class GameRestart {
    private final List<String> command;
    private final Path directory;
    private final Path helperJar;

    private GameRestart(List<String> command, Path directory, Path helperJar) {
        this.command = command;
        this.directory = directory;
        this.helperJar = helperJar;
    }

    public static GameRestart capture(Path configDir, String[] gameArguments) throws IOException {
        return capture(resolveGameDir(configDir), configDir, gameArguments);
    }

    public static GameRestart capture(Path gameDir, Path configDir, String[] gameArguments) throws IOException {
        List<String> command = buildCommand(javaExecutable(), ManagementFactory.getRuntimeMXBean().getInputArguments(),
                System.getProperty("java.class.path", ""), System.getProperty("sun.java.command", ""), gameArguments);
        // Copy the standalone class before any update can replace this mod's own JAR.
        Path helper = configDir.toAbsolutePath().resolve("habitrain-restart-helper.jar");
        Files.createDirectories(helper.getParent());
        Path temporary = helper.resolveSibling(helper.getFileName() + ".tmp");
        String resource = RestartHelper.class.getName().replace('.', '/') + ".class";
        try (InputStream source = RestartHelper.class.getClassLoader().getResourceAsStream(resource)) {
            if (source == null) throw new IOException("Restart helper class unavailable");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temporary))) {
                jar.putNextEntry(new JarEntry(resource));
                source.transferTo(jar);
                jar.closeEntry();
            }
        }
        Files.move(temporary, helper, StandardCopyOption.REPLACE_EXISTING);
        Path resolvedDir = gameDir != null ? gameDir.toAbsolutePath().normalize() : resolveGameDir(configDir);
        return new GameRestart(command, resolvedDir, helper);
    }

    static Path resolveGameDir(Path configDir) {
        if (configDir == null) {
            return Path.of("").toAbsolutePath().normalize();
        }
        Path abs = configDir.toAbsolutePath().normalize();
        Path fileName = abs.getFileName();
        if (fileName != null && "config".equalsIgnoreCase(fileName.toString()) && abs.getParent() != null) {
            return abs.getParent();
        }
        return abs;
    }

    static List<String> buildCommand(String java, List<String> vmArguments, String classpath,
                                     String javaCommand, String[] gameArguments) throws IOException {
        String main = javaCommand.split("\\s+", 2)[0];
        if (!(main.equals("net.fabricmc.loader.impl.launch.knot.KnotClient")
                || main.equals("net.fabricmc.loader.launch.knot.KnotClient")) || classpath.isBlank()) {
            throw new IOException("Automatic restart requires a standard Fabric client launch");
        }
        List<String> result = new ArrayList<>();
        result.add(java);
        for (String argument : vmArguments) {
            // A second debugger cannot bind to the first one's port while the JVM exits.
            if (argument.startsWith("-agentlib:jdwp") || argument.startsWith("-Xrunjdwp")) {
                throw new IOException("Automatic restart unavailable with an attached debugger");
            }
            result.add(argument);
        }
        result.add("-cp");
        result.add(classpath);
        result.add(main);
        result.addAll(Arrays.asList(gameArguments));
        return List.copyOf(result);
    }

    public Prepared prepare() throws Exception {
        Path log = directory.resolve("logs/habitrain-restart.log");
        Files.createDirectories(log.getParent());
        RestartHelper.rotateLogIfOversized(log);
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-cp", helperJar.toString(),
                RestartHelper.class.getName()).directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        RestartHelper.clearInjectedOptions(builder);
        Process process = builder.start();
        Prepared prepared = new Prepared(process);
        try {
            ProcessHandle current = ProcessHandle.current();
            RestartHelper.writePlan(prepared.output, current.pid(),
                    current.info().startInstant().orElseThrow().toEpochMilli(), 90_000, directory, command);
            CompletableFuture<Boolean> ready = new CompletableFuture<>();
            Thread.startVirtualThread(() -> {
                try (DataInputStream in = new DataInputStream(process.getInputStream())) {
                    ready.complete(in.readBoolean());
                } catch (IOException e) {
                    ready.completeExceptionally(e);
                }
            });
            if (!ready.get(10, TimeUnit.SECONDS)) throw new IOException("Restart helper not ready");
            return prepared;
        } catch (Exception e) {
            prepared.close();
            throw e;
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java").toString();
    }

    public static final class Prepared implements AutoCloseable {
        private final Process process;
        private final DataOutputStream output;
        private boolean committed;

        private Prepared(Process process) {
            this.process = process;
            this.output = new DataOutputStream(process.getOutputStream());
        }

        public void commit() throws IOException {
            output.writeByte(1);
            output.flush();
            committed = true;
        }

        @Override
        public void close() {
            try { output.close(); } catch (IOException ignored) { }
            if (!committed) {
                try {
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroy();
                        if (!process.waitFor(3, TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                            process.waitFor(3, TimeUnit.SECONDS);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
