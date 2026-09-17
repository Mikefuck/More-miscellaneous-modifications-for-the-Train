package com.habitrain.lottery.title;

import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalTitleStoreTest {
    @TempDir Path temp;

    @AfterEach
    void tearDown() {
        LocalTitleStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void catalogRoundTripAndPlayerGrantDedupe() throws Exception {
        // Test store against temp root via package-visible test hook OR
        // pure static helpers: serialize catalog JSON with Gson and PlayerTitleData grant logic.
        PlayerTitleData d = new PlayerTitleData();
        // first grant succeeds; duplicate is rejected (dedupe)
        assertTrue(LocalTitleStore.grantInMemory(d, "§6[服主]"));
        assertFalse(LocalTitleStore.grantInMemory(d, "§6[服主]"));
        assertEquals(1, d.owned.size());
        assertTrue(LocalTitleStore.grantInMemory(d, "§b[赞助]"));
        assertTrue(LocalTitleStore.setCurrentInMemory(d, "§b[赞助]"));
        assertEquals("§b[赞助]", d.current);
        assertFalse(LocalTitleStore.setCurrentInMemory(d, "nope"));
        LocalTitleStore.revokeInMemory(d, "§b[赞助]");
        assertEquals("", d.current);
        assertEquals(1, d.owned.size());
    }

    @Test
    void contentEqualsIgnoresUpdatedAt() {
        PlayerTitleData a = new PlayerTitleData();
        a.owned.add("§6[服主]");
        a.current = "§6[服主]";
        a.updatedAt = 1L;
        PlayerTitleData b = new PlayerTitleData();
        b.owned.add("§6[服主]");
        b.current = "§6[服主]";
        b.updatedAt = 99L;
        assertTrue(a.contentEquals(b));
        b.current = "";
        assertFalse(a.contentEquals(b));
    }

    @Test
    void resetDropsCatalogCacheSoLoadRereadsDisk() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        TitlePaths.ensureDirs();
        Path catalog = TitlePaths.catalogFile();
        Files.writeString(catalog,
                "{\"version\":1,\"titles\":[{\"id\":\"old\",\"display\":\"Old\",\"enabled\":true}]}",
                StandardCharsets.UTF_8);

        TitleCatalog cached = LocalTitleStore.get().loadCatalog();
        assertEquals(1, cached.titles.size());
        assertEquals("old", cached.titles.get(0).id);

        LocalTitleStore.get().reset();
        Files.writeString(catalog,
                "{\"version\":1,\"titles\":[{\"id\":\"next\",\"display\":\"Next\",\"enabled\":true}]}",
                StandardCharsets.UTF_8);

        TitleCatalog reloaded = LocalTitleStore.get().loadCatalog();
        assertEquals(1, reloaded.titles.size());
        assertEquals("next", reloaded.titles.get(0).id);
    }

    @Test
    void corruptPlayerFileDoesNotYieldEmptyOwnedThatWouldBeSaved() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        TitlePaths.ensureDirs();
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Path file = TitlePaths.playerFile(id);
        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);

        LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(id);
        assertTrue(load.corrupt());
        assertNull(load.data());
        assertFalse(Files.isRegularFile(file));
        assertEquals(TitleService.JoinPlan.KEEP_CCA, TitleService.planJoin(load, true));
        assertEquals(TitleService.JoinPlan.KEEP_CCA, TitleService.planJoin(load, false));
        assertFalse(LocalTitleStore.get().grant(id, "§6[x]"));
        assertFalse(Files.isRegularFile(file));
    }

    @Test
    void missingPlayerFileDoesNotPlanEmptyOverwrite() {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(id);
        assertTrue(load.missing());
        assertEquals(TitleService.JoinPlan.KEEP_CCA, TitleService.planJoin(load, false));
        assertEquals(TitleService.JoinPlan.MIGRATE_CCA, TitleService.planJoin(load, true));
    }
}
