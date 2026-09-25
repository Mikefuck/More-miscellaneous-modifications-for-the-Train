package com.habitrain.lottery.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyConfigRetirementTest {
    @TempDir Path temp;

    @Test
    void removesOnlyRetiredSettingsAndTheirBackups() throws Exception {
        Path config = temp.resolve("config");
        Files.createDirectories(config);
        Path pool = config.resolve("pools.json");
        Path quality = config.resolve("skins_quality.json");
        Path backup = config.resolve("pools.json.bak");
        Path independent = config.resolve("unrelated.json");
        Path nested = config.resolve("nested").resolve("pools.json");
        Files.createDirectories(nested.getParent());
        for (Path path : new Path[]{pool, quality, backup, independent, nested}) {
            Files.writeString(path, "{}");
        }

        assertTrue(LegacyConfigRetirement.retire(config));
        assertFalse(Files.exists(pool));
        assertFalse(Files.exists(quality));
        assertFalse(Files.exists(backup));
        assertTrue(Files.exists(independent));
        assertTrue(Files.exists(nested));
    }
}
