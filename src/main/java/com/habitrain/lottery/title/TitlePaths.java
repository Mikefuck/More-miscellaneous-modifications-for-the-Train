package com.habitrain.lottery.title;

import com.habitrain.lottery.storage.WorldLotteryPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Paths under {@code {world}/habitrain_lottery/titles/}.
 */
public final class TitlePaths {
    private TitlePaths() {
    }

    public static Path titlesDir() {
        Path root = WorldLotteryPaths.root();
        return root == null ? null : resolveUnder(root, "titles");
    }

    public static Path catalogFile() {
        Path dir = titlesDir();
        return dir == null ? null : resolveUnder(dir, "catalog.json");
    }

    public static Path playersDir() {
        Path dir = titlesDir();
        return dir == null ? null : resolveUnder(dir, "players");
    }

    public static Path playerFile(UUID id) {
        Path dir = playersDir();
        if (dir == null || id == null) {
            return null;
        }
        return resolveUnder(dir, id + ".json");
    }

    public static void ensureDirs() throws IOException {
        Path players = playersDir();
        if (players == null) {
            return;
        }
        Files.createDirectories(players);
    }

    private static Path resolveUnder(Path base, String child) {
        return WorldLotteryPaths.resolveChildFile(base, child);
    }
}
