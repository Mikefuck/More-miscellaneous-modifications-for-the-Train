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
        return WorldLotteryPaths.root().resolve("titles");
    }

    public static Path catalogFile() {
        return titlesDir().resolve("catalog.json");
    }

    public static Path playersDir() {
        return titlesDir().resolve("players");
    }

    public static Path playerFile(UUID id) {
        return playersDir().resolve(id + ".json");
    }

    public static void ensureDirs() throws IOException {
        if (!WorldLotteryPaths.ready()) {
            return;
        }
        Files.createDirectories(playersDir());
    }
}
