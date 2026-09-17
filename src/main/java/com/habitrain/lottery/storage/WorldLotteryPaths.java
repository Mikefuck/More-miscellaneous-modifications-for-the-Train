package com.habitrain.lottery.storage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class WorldLotteryPaths {
    public static final String ROOT_DIR = "habitrain_lottery";

    private static Path root;
    private static Path configDir;
    private static Path playersDir;
    private static Path historyDir;

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
        root = null;
        configDir = null;
        playersDir = null;
        historyDir = null;
    }

    /** Alias for {@link #clear()} used by shutdown. */
    public static void reset() {
        clear();
    }

    private static void applyRoot(Path lotteryRoot) {
        Path nextConfig = lotteryRoot.resolve("config");
        Path nextPlayers = lotteryRoot.resolve("players");
        Path nextHistory = lotteryRoot.resolve("history");
        try {
            Files.createDirectories(nextConfig);
            Files.createDirectories(nextPlayers);
            Files.createDirectories(nextHistory);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create habitrain_lottery world dirs", e);
        }
        root = lotteryRoot;
        configDir = nextConfig;
        playersDir = nextPlayers;
        historyDir = nextHistory;
    }

    public static Path root() {
        return root;
    }

    public static Path configDir() {
        return configDir;
    }

    public static Path playersDir() {
        return playersDir;
    }

    public static Path historyDir() {
        return historyDir;
    }

    public static Path playerFile(UUID uuid) {
        Path dir = playersDir;
        return dir == null ? null : dir.resolve(uuid.toString() + ".json");
    }

    public static Path historyFile(UUID uuid) {
        Path dir = historyDir;
        return dir == null ? null : dir.resolve(uuid.toString() + ".jsonl");
    }

    public static Path configFile(String name) {
        Path dir = configDir;
        return dir == null ? null : dir.resolve(name);
    }

    public static boolean ready() {
        return root != null;
    }

    /** World save root (parent of habitrain_lottery). Null if not ready. */
    public static Path worldRoot() {
        return root == null ? null : root.getParent();
    }

    /** world/lottery_backup — sibling of habitrain_lottery. */
    public static Path backupRoot() {
        Path wr = worldRoot();
        return wr == null ? null : wr.resolve("lottery_backup");
    }
}
