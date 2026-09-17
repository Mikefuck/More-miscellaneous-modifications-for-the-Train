package com.habitrain.lottery.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldLotteryPathsTest {
    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        WorldLotteryPaths.clear();
    }

    @Test
    void initForTestsReadyThenClearDropsRoot() {
        WorldLotteryPaths.initForTests(temp);
        assertTrue(WorldLotteryPaths.ready());
        assertNotNull(WorldLotteryPaths.root());
        assertTrue(Files.isDirectory(WorldLotteryPaths.configDir()));
        assertTrue(Files.isDirectory(WorldLotteryPaths.playersDir()));
        assertTrue(Files.isDirectory(WorldLotteryPaths.historyDir()));

        WorldLotteryPaths.clear();
        assertFalse(WorldLotteryPaths.ready());
        assertNull(WorldLotteryPaths.root());
        assertNull(WorldLotteryPaths.configDir());
        assertNull(WorldLotteryPaths.playersDir());
        assertNull(WorldLotteryPaths.historyDir());
        assertNull(WorldLotteryPaths.playerFile(UUID.randomUUID()));
    }

    @Test
    void mkdirFailureLeavesReadyFalse() throws Exception {
        Path blocker = temp.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> WorldLotteryPaths.initForTests(blocker));
        assertFalse(WorldLotteryPaths.ready());
        assertNull(WorldLotteryPaths.root());
        assertNotNull(ex.getCause());
    }
}
