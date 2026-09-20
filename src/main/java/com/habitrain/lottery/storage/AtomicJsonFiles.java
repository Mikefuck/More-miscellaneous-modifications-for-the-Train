package com.habitrain.lottery.storage;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Same-directory atomic replace for world/config JSON (and other small files).
 *
 * <p>Write path: a <b>uniquely named</b> sibling temp file in the same directory
 * ({@code <name>.<jvm-tag>-<seq>.tmp}) → optional fsync → copy live file to {@code .bak}
 * (abort replace if that copy fails) → {@code ATOMIC_MOVE} with {@code REPLACE_EXISTING}
 * fallback. Callers must treat a {@code false} return as failure and must not clear dirty
 * flags or announce success.
 *
 * <p><b>Audit S-01.</b> The temp name used to be the fixed {@code <target>.tmp}. Two
 * server processes sharing one game directory therefore interleaved their writes into
 * that single file and could move a half-written mixture over the live file. The name is
 * now unique per JVM and per write (a random per-JVM tag plus a monotonic sequence),
 * allocated with {@code CREATE_NEW} so a name can never be reused by another process's
 * in-flight write. Temp files still live in the target's own directory so
 * {@code ATOMIC_MOVE} stays atomic, and a temp file that was not moved is always deleted.
 *
 * <p>Read path: parse primary; on failure quarantine to {@code .corrupt-<ts>} and try
 * {@code .bak}. Missing primary still tries {@code .bak}. Both unreadable → {@code CORRUPT}
 * (never a silent empty object). Neither exists → {@code MISSING}.
 */
public final class AtomicJsonFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_lottery|AtomicJsonFiles");

    /**
     * Random per-JVM token mixed into every temp file name (audit S-01). Two server
     * processes on the same directory draw different tokens, and {@code CREATE_NEW}
     * turns the remaining (astronomically unlikely) collision into a retry instead of
     * two writers sharing one temp file.
     */
    private static final String TEMP_TAG =
            java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    /** Monotonic per-JVM counter so concurrent writers in one JVM never share a temp name. */
    private static final java.util.concurrent.atomic.AtomicLong TEMP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    /** Attempts allowed when a randomly named temp file already exists. */
    private static final int TEMP_NAME_ATTEMPTS = 4;

    private AtomicJsonFiles() {
    }

    public static Path bakPath(Path target) {
        return target.resolveSibling(target.getFileName().toString() + ".bak");
    }

    public static boolean writeJson(Path target, Object value, Gson gson, boolean fsync) {
        return writeJson(target, value, gson, fsync, true);
    }

    public static boolean writeJson(Path target, Object value, Gson gson, boolean fsync, boolean backup) {
        if (target == null || value == null || gson == null) {
            return false;
        }
        return write(target, out -> {
            // Do not close `out` here: the outer write() still fsyncs/flushes it.
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            gson.toJson(value, writer);
            writer.flush();
        }, fsync, backup);
    }

    public static boolean writeString(Path target, String text, Charset charset, boolean fsync, boolean backup) {
        if (target == null || text == null) {
            return false;
        }
        Charset cs = charset == null ? StandardCharsets.UTF_8 : charset;
        byte[] bytes = text.getBytes(cs);
        return writeBytes(target, bytes, fsync, backup);
    }

    public static boolean writeBytes(Path target, byte[] bytes, boolean fsync, boolean backup) {
        if (target == null || bytes == null) {
            return false;
        }
        return write(target, out -> out.write(bytes), fsync, backup);
    }

    public static boolean copyFrom(InputStream in, Path target, boolean fsync, boolean backup) {
        if (in == null || target == null) {
            return false;
        }
        return write(target, out -> in.transferTo(out), fsync, backup);
    }

    public static boolean copyFile(Path source, Path target, boolean fsync, boolean backup) {
        if (source == null || target == null || !Files.isRegularFile(source)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(source)) {
            return copyFrom(in, target, fsync, backup);
        } catch (IOException e) {
            LOGGER.error("Failed copying {} -> {}", source, target, e);
            return false;
        }
    }

    public static <T> JsonLoad<T> readJson(Path target, Class<T> type, Gson gson) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(gson, "gson");
        if (target == null) {
            return JsonLoad.missing();
        }
        Path bak = bakPath(target);
        if (Files.isRegularFile(target)) {
            T parsed = tryParse(target, type, gson);
            if (parsed != null) {
                return JsonLoad.ok(parsed);
            }
            LOGGER.error("Unreadable JSON {}, quarantining and trying .bak", target);
            Path quarantined = quarantine(target);
            T fromBak = tryParse(bak, type, gson);
            if (fromBak != null) {
                return JsonLoad.fromBackup(fromBak, quarantined);
            }
            return JsonLoad.corrupt(quarantined);
        }
        T fromBak = tryParse(bak, type, gson);
        if (fromBak != null) {
            LOGGER.warn("Primary {} missing; restored from .bak", target);
            return JsonLoad.fromBackup(fromBak, null);
        }
        return JsonLoad.missing();
    }

    /**
     * Move {@code file} aside so it cannot be treated as live authority.
     * Returns the quarantine path, or {@code null} if the file could not be moved.
     */
    public static Path quarantine(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        Path dest = file.resolveSibling(file.getFileName().toString() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Quarantined unreadable file to {}", dest);
            return dest;
        } catch (IOException moveFailed) {
            try {
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(file);
                LOGGER.warn("Quarantined unreadable file (copy) to {}", dest);
                return dest;
            } catch (IOException copyFailed) {
                LOGGER.error("Failed quarantining {}", file, copyFailed);
                return null;
            }
        }
    }

    private static <T> T tryParse(Path file, Class<T> type, Gson gson) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, type);
        } catch (Exception e) {
            LOGGER.warn("Failed parsing {}: {}", file, e.toString());
            return null;
        }
    }

    private static boolean write(Path target, IoConsumer consumer, boolean fsync, boolean backup) {
        Path parent = target.toAbsolutePath().getParent();
        Path tmp = null;
        boolean moved = false;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Audit S-01: never a fixed `<target>.tmp` — see the class javadoc.
            tmp = createUniqueTemp(target);
            try (OutputStream out = Files.newOutputStream(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                consumer.accept(out);
                out.flush();
            }
            if (fsync) {
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            if (backup && Files.isRegularFile(target)) {
                Path bak = bakPath(target);
                try {
                    if (Files.exists(bak) && !Files.isRegularFile(bak)) {
                        throw new IOException(".bak is not a regular file: " + bak);
                    }
                    Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    LOGGER.error("Refusing to replace {} because .bak copy failed", target, e);
                    return false;
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return true;
        } catch (Exception e) {
            LOGGER.error("Atomic write failed for {}", target, e);
            return false;
        } finally {
            // The temp file only ever disappears through the move that publishes it.
            if (!moved && tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Reserves a fresh, empty sibling temp file next to {@code target} with
     * {@code CREATE_NEW}, so the returned path is owned by this writer and can never be
     * shared with another process or thread (audit S-01). Same directory as the target,
     * which is what keeps the final {@code ATOMIC_MOVE} atomic.
     */
    private static Path createUniqueTemp(Path target) throws IOException {
        String name = target.getFileName().toString();
        IOException lastCollision = null;
        for (int attempt = 0; attempt < TEMP_NAME_ATTEMPTS; attempt++) {
            Path candidate = target.resolveSibling(
                    name + "." + TEMP_TAG + "-" + TEMP_SEQUENCE.incrementAndGet() + ".tmp");
            try {
                Files.createFile(candidate);
                return candidate;
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                lastCollision = collision;
            }
        }
        throw new IOException("Could not allocate a unique temporary file next to " + target, lastCollision);
    }

    @FunctionalInterface
    private interface IoConsumer {
        void accept(OutputStream out) throws IOException;
    }

    public static final class JsonLoad<T> {
        public enum Status {
            OK,
            OK_BACKUP,
            MISSING,
            CORRUPT
        }

        private final T value;
        private final Status status;
        private final Path quarantined;

        private JsonLoad(T value, Status status, Path quarantined) {
            this.value = value;
            this.status = status;
            this.quarantined = quarantined;
        }

        public static <T> JsonLoad<T> ok(T value) {
            return new JsonLoad<>(value, Status.OK, null);
        }

        public static <T> JsonLoad<T> fromBackup(T value, Path quarantined) {
            return new JsonLoad<>(value, Status.OK_BACKUP, quarantined);
        }

        public static <T> JsonLoad<T> missing() {
            return new JsonLoad<>(null, Status.MISSING, null);
        }

        public static <T> JsonLoad<T> corrupt(Path quarantined) {
            return new JsonLoad<>(null, Status.CORRUPT, quarantined);
        }

        public T value() {
            return value;
        }

        public Status status() {
            return status;
        }

        public Path quarantined() {
            return quarantined;
        }

        public boolean ok() {
            return status == Status.OK || status == Status.OK_BACKUP;
        }

        public boolean usedBackup() {
            return status == Status.OK_BACKUP;
        }

        public boolean isMissing() {
            return status == Status.MISSING;
        }

        public boolean corrupt() {
            return status == Status.CORRUPT;
        }
    }
}
