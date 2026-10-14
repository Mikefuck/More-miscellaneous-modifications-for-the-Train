package com.habitrain.lottery.crate;

import com.google.gson.Gson;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CrateConfigLoadTest {
    @TempDir Path root;

    @AfterEach void cleanup() {
        CrateService.onServerStopping();
        WorldLotteryPaths.clear();
    }

    @Test void startupRejectsExtremeRollCountAndKeepsOriginalFile() throws Exception {
        WorldLotteryPaths.initForTests(root);
        var state = new CrateService.State();
        state.schemaVersion = 5;
        var pool = new CrateService.CratePool();
        pool.name = "Extreme";
        pool.keyName = "Extreme key";
        pool.customPool = true;
        pool.rewardMode = "unified_pool";
        pool.rollCount = Integer.MAX_VALUE;
        state.crates.put("woodland", pool);
        Path config = WorldLotteryPaths.configFile("crates.json");
        String before = new Gson().toJson(state);
        Files.writeString(config, before);
        assertDoesNotThrow(CrateService::onServerStarted);
        assertEquals("crates.not_ready", CrateService.open(null, "woodland", "key_woodland").message());
        assertEquals(before, Files.readString(config));
    }
}
