package com.habitrain.lottery.config;

import com.habitrain.lottery.storage.AtomicJsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LotteryConfigServiceTest {

    @TempDir
    Path tmp;

    @Test
    void writeJsonLeavesValidFileAndBakOfPrevious() throws Exception {
        Path file = tmp.resolve("pools.json");
        String previous = "{\"Pools\":[{\"PoolName\":\"v1\"}]}";
        Files.writeString(file, previous, StandardCharsets.UTF_8);

        PoolConfigModels.Root next = new PoolConfigModels.Root();
        PoolConfigModels.Pool pool = new PoolConfigModels.Pool();
        pool.PoolName = "v2";
        pool.PoolID = 2;
        next.Pools.add(pool);

        assertTrue(LotteryConfigService.writeJson(file, next));

        String live = Files.readString(file, StandardCharsets.UTF_8);
        PoolConfigModels.Root parsed = LotteryConfigService.GSON.fromJson(live, PoolConfigModels.Root.class);
        assertNotNull(parsed);
        assertNotNull(parsed.Pools);
        assertEquals(1, parsed.Pools.size());
        assertEquals("v2", parsed.Pools.get(0).PoolName);
        assertEquals(previous, Files.readString(AtomicJsonFiles.bakPath(file), StandardCharsets.UTF_8).trim());
        assertFalse(Files.exists(file.resolveSibling("pools.json.tmp")));
    }

    @Test
    void corruptPoolsDoesNotGetOverwrittenBySaveAll() throws Exception {
        Path dir = tmp.resolve("config");
        Files.createDirectories(dir);
        Path pools = dir.resolve("pools.json");
        Files.writeString(dir.resolve("meta.json"), "{\"config_version\":4}", StandardCharsets.UTF_8);
        Files.writeString(pools, "{not-json", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(pools), "{also-bad", StandardCharsets.UTF_8);

        LotteryConfigService svc = new LotteryConfigService();
        svc.loadOrSeed(dir);
        assertTrue(svc.isLoadFailed());

        PoolConfigModels.Root injected = new PoolConfigModels.Root();
        PoolConfigModels.Pool pool = new PoolConfigModels.Pool();
        pool.PoolName = "SHOULD_NOT_PERSIST";
        pool.PoolID = 99;
        injected.Pools.add(pool);
        svc.setPools(injected);
        assertTrue(svc.isDirty());
        assertFalse(svc.saveAll(dir));

        if (Files.isRegularFile(pools)) {
            assertFalse(Files.readString(pools, StandardCharsets.UTF_8).contains("SHOULD_NOT_PERSIST"));
        }
        boolean sawQuarantine = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "pools.json.corrupt-*")) {
            for (Path q : stream) {
                sawQuarantine = true;
                assertFalse(Files.readString(q, StandardCharsets.UTF_8).contains("SHOULD_NOT_PERSIST"));
            }
        }
        assertTrue(sawQuarantine);
    }

    @Test
    void migrateFromVersion3DoesNotReplacePools() throws Exception {
        Path dir = tmp.resolve("config");
        Files.createDirectories(dir);
        String customPools = "{\"Pools\":[{\"PoolName\":\"server-owner-pool\",\"PoolID\":7}]}";
        Files.writeString(dir.resolve("pools.json"), customPools, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("rates.json"),
                "{\"coinPerDraw\":200,\"duplicateCoinFlat\":99}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("grants.json"),
                "{\"events\":[{\"id\":\"old\",\"amount\":1}]}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("meta.json"), "{\"config_version\":3}", StandardCharsets.UTF_8);

        LotteryConfigService svc = new LotteryConfigService();
        svc.loadOrSeed(dir);

        assertEquals(customPools, Files.readString(dir.resolve("pools.json"), StandardCharsets.UTF_8));
        assertFalse(svc.isLoadFailed());
        assertEquals(4, svc.getMeta().config_version);
        assertEquals(200, svc.getRates().coinPerDraw);
        assertEquals(99, svc.getRates().duplicateCoinFlat);
        assertNotNull(svc.getGrants().find("win_passenger"));
        assertNull(svc.getGrants().find("old"));

        boolean backupFound = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "backup_v3_*")) {
            for (Path backup : stream) {
                backupFound = true;
                assertEquals(customPools, Files.readString(backup.resolve("pools.json"), StandardCharsets.UTF_8));
                assertTrue(Files.readString(backup.resolve("grants.json"), StandardCharsets.UTF_8).contains("old"));
            }
        }
        assertTrue(backupFound);
    }
}
