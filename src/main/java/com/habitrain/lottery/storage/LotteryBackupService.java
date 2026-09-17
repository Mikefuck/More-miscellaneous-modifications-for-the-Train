package com.habitrain.lottery.storage;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.title.LocalTitleStore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class LotteryBackupService {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private LotteryBackupService() {
    }

    public record Result(boolean ok, String message, Path snapshotDir) {
        public static Result fail(String message) {
            return new Result(false, message, null);
        }

        public static Result ok(String message, Path dir) {
            return new Result(true, message, dir);
        }
    }

    /** Flush dirty player data, then copy world/habitrain_lottery → world/lottery_backup/<ts>/. */
    public static Result createTimestampedBackup() {
        if (!WorldLotteryPaths.ready()) {
            return Result.fail("备份失败: 世界未加载");
        }
        Path source = WorldLotteryPaths.root();
        if (source == null || !Files.isDirectory(source)) {
            return Result.fail("备份失败: habitrain_lottery 不存在");
        }
        Path backupRoot = WorldLotteryPaths.backupRoot();
        if (backupRoot == null) {
            return Result.fail("备份失败: 世界路径无效");
        }
        try {
            PlayerLotteryStore.get().flushAll();
            LocalTitleStore.get().flushAll();
            Files.createDirectories(backupRoot);
            String ts = LocalDateTime.now().format(TS);
            Path dest = resolveBackupFolder(backupRoot, ts);
            int n = 0;
            while (dest != null && Files.exists(dest)) {
                n++;
                dest = resolveBackupFolder(backupRoot, ts + "-" + n);
                if (n > 1000) {
                    return Result.fail("备份失败: 无法分配目录名");
                }
            }
            if (dest == null || !isUnder(backupRoot, dest)) {
                return Result.fail("备份失败: 目标路径越界");
            }
            try {
                copyTree(source, dest);
            } catch (Exception copyEx) {
                deleteTreeBestEffort(dest);
                deleteTreeBestEffort(partialDir(dest));
                throw copyEx;
            }
            String rel = "lottery_backup/" + dest.getFileName();
            HabiLotteryMod.LOGGER.info("Created lottery backup at {}", dest);
            return Result.ok("备份已创建: " + rel, dest);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("lottery backup failed", e);
            return Result.fail("备份失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * Copy {@code source} into {@code dest} by filling {@code dest.partial} first, then renaming.
     */
    public static void copyTree(Path source, Path dest) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            throw new IOException("source missing: " + source);
        }
        if (dest == null) {
            throw new IOException("dest missing");
        }
        Path destAbs = dest.toAbsolutePath().normalize();
        if (destAbs.getFileName() == null) {
            throw new IOException("dest has no file name: " + destAbs);
        }
        Path parent = destAbs.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path partial = partialDir(destAbs);
        if (partial == null || (parent != null && !isUnder(parent, partial))) {
            throw new IOException("partial dest escaped: " + partial);
        }
        deleteTreeBestEffort(partial);
        if (Files.exists(destAbs)) {
            throw new IOException("dest exists: " + destAbs);
        }
        try {
            copyTreeInto(source, partial);
            try {
                Files.move(partial, destAbs, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, destAbs, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            deleteTreeBestEffort(partial);
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException(e);
        }
    }

    static Path resolveBackupFolder(Path backupRoot, String folderName) {
        if (backupRoot == null || folderName == null || folderName.isBlank()) {
            return null;
        }
        if (folderName.equals(".") || folderName.equals("..") || folderName.contains("..")) {
            return null;
        }
        for (int i = 0; i < folderName.length(); i++) {
            char c = folderName.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c == ':') {
                return null;
            }
        }
        Path rootAbs = backupRoot.toAbsolutePath().normalize();
        Path dest = rootAbs.resolve(folderName).normalize();
        if (!isUnder(rootAbs, dest)) {
            return null;
        }
        return dest;
    }

    static boolean isUnder(Path root, Path candidate) {
        if (root == null || candidate == null) {
            return false;
        }
        Path r = root.toAbsolutePath().normalize();
        Path c = candidate.toAbsolutePath().normalize();
        return c.startsWith(r) && !c.equals(r);
    }

    static Path partialDir(Path dest) {
        if (dest == null) {
            return null;
        }
        Path abs = dest.toAbsolutePath().normalize();
        Path name = abs.getFileName();
        if (name == null) {
            return null;
        }
        return abs.resolveSibling(name.toString() + ".partial");
    }

    private static void copyTreeInto(Path source, Path dest) throws IOException {
        Path destAbs = dest.toAbsolutePath().normalize();
        Files.createDirectories(destAbs);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Path target = destAbs.resolve(rel.toString()).normalize();
                if (!target.startsWith(destAbs)) {
                    throw new IOException("backup copy escaped dest: " + rel);
                }
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Path target = destAbs.resolve(rel.toString()).normalize();
                if (!target.startsWith(destAbs)) {
                    throw new IOException("backup copy escaped dest: " + rel);
                }
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Best-effort recursive delete used to scrub a partial backup snapshot. */
    static void deleteTreeBestEffort(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            HabiLotteryMod.LOGGER.warn("Failed to clean partial backup at {}: {}", root, e.toString());
        }
    }
}
