package com.habitrain.lottery.storage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class WorldLotteryPaths {
    public static final String ROOT_DIR = "habitrain_lottery";

    private record PathsSnapshot(Path root, Path configDir, Path playersDir) {
    }

    private static volatile PathsSnapshot CURRENT;

    private WorldLotteryPaths() {
    }

    public static void init(MinecraftServer server) {
        applyRoot(server.getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR).toAbsolutePath().normalize());
    }

    /** Test hook: use the given directory as the habitrain_lottery root. */
    public static void initForTests(Path lotteryRoot) {
        if (lotteryRoot == null) {
            throw new IllegalArgumentException("lotteryRoot");
        }
        applyRoot(lotteryRoot.toAbsolutePath().normalize());
    }

    /**
     * Test hook: set the world lottery root to {@code worldRoot/habitrain_lottery}
     * without a {@link MinecraftServer}.
     */
    public static void initForTest(Path worldRoot) {
        if (worldRoot == null) {
            throw new IllegalArgumentException("worldRoot");
        }
        applyRoot(worldRoot.resolve(ROOT_DIR).toAbsolutePath().normalize());
    }

    public static void clear() {
        com.habitrain.lottery.card.SelfSelectRoleConfigService.reset();
        CURRENT = null;
        // Shutdown flushes before unbinding. Never carry cached assets (or failure
        // flags) into another world, and never flush them against a new root.
        PlayerLotteryStore.get().reset();
        com.habitrain.lottery.title.LocalTitleStore.get().reset();
    }

    /** Alias for {@link #clear()} used by shutdown. */
    public static void reset() {
        clear();
    }

    private static void applyRoot(Path lotteryRoot) {
        Path normalizedRoot = lotteryRoot.toAbsolutePath().normalize();
        Path nextConfig = resolveUnder(normalizedRoot, "config");
        Path nextPlayers = resolveUnder(normalizedRoot, "players");
        try {
            Files.createDirectories(nextConfig);
            Files.createDirectories(nextPlayers);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create habitrain_lottery world dirs", e);
        }
        clear();
        CURRENT = new PathsSnapshot(normalizedRoot, nextConfig, nextPlayers);
    }

    public static Path root() {
        PathsSnapshot s = CURRENT;
        return s == null ? null : s.root();
    }

    public static Path configDir() {
        PathsSnapshot s = CURRENT;
        return s == null ? null : s.configDir();
    }

    public static Path playersDir() {
        PathsSnapshot s = CURRENT;
        return s == null ? null : s.playersDir();
    }

    public static Path playerFile(UUID uuid) {
        PathsSnapshot s = CURRENT;
        if (s == null || uuid == null) {
            return null;
        }
        return resolveChildFile(s.playersDir(), uuid.toString() + ".json");
    }

    public static Path configFile(String name) {
        PathsSnapshot s = CURRENT;
        if (s == null) {
            return null;
        }
        return resolveChildFile(s.configDir(), name);
    }

    public static boolean ready() {
        return CURRENT != null;
    }

    /** World save root (parent of habitrain_lottery). Null if not ready. */
    public static Path worldRoot() {
        PathsSnapshot s = CURRENT;
        return s == null ? null : s.root().getParent();
    }

    /** world/lottery_backup — sibling of habitrain_lottery. */
    public static Path backupRoot() {
        Path wr = worldRoot();
        return wr == null ? null : resolveUnder(wr, "lottery_backup");
    }

    public static Path resolveUnder(Path base, String child) {
        if (base == null || child == null || child.isBlank()) {
            throw new IllegalArgumentException("Invalid child path: " + child);
        }
        Path baseAbs = base.toAbsolutePath().normalize();
        Path resolved = baseAbs.resolve(child).normalize();
        if (!resolved.startsWith(baseAbs) || resolved.equals(baseAbs)) {
            throw new IllegalArgumentException("Path escapes base directory: " + child);
        }
        return resolved;
    }

    public static Path resolveChildFile(Path dir, String fileName) {
        if (dir == null) {
            return null;
        }
        if (fileName == null || fileName.isBlank() || ".".equals(fileName) || "..".equals(fileName)
                || fileName.contains("..")) {
            throw new IllegalArgumentException("Unsafe file name: " + fileName);
        }
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c == ':') {
                throw new IllegalArgumentException("Unsafe file name: " + fileName);
            }
        }
        Path baseAbs = dir.toAbsolutePath().normalize();
        Path resolved = baseAbs.resolve(fileName).normalize();
        if (!resolved.startsWith(baseAbs) || !baseAbs.equals(resolved.getParent())) {
            throw new IllegalArgumentException("Path escapes directory: " + fileName);
        }
        return resolved;
    }
}
