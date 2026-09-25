package com.habitrain.lottery.config;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.WorldLotteryPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Removes only obsolete lottery settings from this world's configuration directory. */
public final class LegacyConfigRetirement {
    private static final List<String> RETIRED_FILES = List.of(
            "pools.json", "rates.json", "grants.json", "theme.json", "meta.json",
            "skins_quality.json");

    private LegacyConfigRetirement() {
    }

    public static boolean retire() {
        return retire(WorldLotteryPaths.configDir());
    }

    static boolean retire(Path configDir) {
        if (configDir == null) {
            return false;
        }
        Path verifiedDir = configDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(verifiedDir);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Cannot create player config directory {}", verifiedDir, e);
            return false;
        }
        boolean ok = true;
        for (String name : RETIRED_FILES) {
            Path primary = verifiedDir.resolve(name).normalize();
            Path backup = AtomicJsonFiles.bakPath(primary).toAbsolutePath().normalize();
            if (!verifiedDir.equals(primary.getParent()) || !verifiedDir.equals(backup.getParent())) {
                throw new IllegalStateException("Retired config path escaped " + verifiedDir);
            }
            try {
                Files.deleteIfExists(primary);
                Files.deleteIfExists(backup);
            } catch (Exception e) {
                ok = false;
                HabiLotteryMod.LOGGER.error("Could not retire obsolete config {}", name, e);
            }
        }
        return ok;
    }
}
