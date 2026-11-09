package com.habitrain.lottery.title;

import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalTitleStoreCorruptGuardTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        LocalTitleStore.get().reset();
        WorldLotteryPaths.initForTests(temp);
    }

    @AfterEach
    void tearDown() {
        LocalTitleStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void offlineAdminQueriesKeepABoundedCacheAndReloadEvictedAssetsFromDisk() {
        LocalTitleStore store = LocalTitleStore.get();
        UUID owner = UUID.randomUUID();
        PlayerTitleData data = new PlayerTitleData();
        data.owned = List.of("retained-title");
        assertTrue(store.savePlayer(owner, data));
        for (int i = 0; i < 1_000; i++) store.loadPlayerDetailed(new UUID(0, i));
        assertTrue(store.cachedPlayersForTest() <= LocalTitleStore.MAX_OFFLINE_CACHE);
        assertEquals(List.of("retained-title"), store.loadPlayerDetailed(owner).data().owned);
        assertTrue(store.cachedPlayersForTest() <= LocalTitleStore.MAX_OFFLINE_CACHE);
    }

    @Test
    void corruptCatalogSetsFlagRefusesOverwriteAndDoesNotCacheEmpty() throws Exception {
        TitlePaths.ensureDirs();
        Path catalogFile = TitlePaths.catalogFile();
        Files.writeString(catalogFile, "{corrupt-catalog-json", StandardCharsets.UTF_8);

        LocalTitleStore store = LocalTitleStore.get();
        TitleCatalog loaded = store.loadCatalog();
        assertTrue(loaded.titles.isEmpty());
        assertTrue(store.isCatalogCorrupt());

        TitleCatalog replacement = new TitleCatalog();
        replacement.titles.add(new TitleCatalog.TitleEntry("vip", "§6[VIP]", true));
        assertFalse(store.saveCatalogChecked(replacement), "Must refuse to overwrite corrupt catalog");
        assertFalse(Files.isRegularFile(catalogFile), "Corrupt catalog must remain quarantined, not overwritten");

        // Even after reset (when saveCatalogChecked is called before loadCatalog), quarantine evidence blocks overwrite
        store.reset();
        assertFalse(store.saveCatalogChecked(replacement),
                "saveCatalogChecked before loadCatalog must probe disk and refuse overwrite when quarantined");
        assertTrue(store.isCatalogCorrupt());
    }

    @Test
    void catalogBackupRecoversFromCorruptPrimary() throws Exception {
        LocalTitleStore store = LocalTitleStore.get();
        TitleCatalog v1 = new TitleCatalog();
        v1.titles.add(new TitleCatalog.TitleEntry("founder", "§e[元老]", true));
        assertTrue(store.saveCatalogChecked(v1));

        TitleCatalog v2 = new TitleCatalog();
        v2.titles.add(new TitleCatalog.TitleEntry("founder", "§e[元老]", true));
        v2.titles.add(new TitleCatalog.TitleEntry("mvp", "§b[MVP]", true));
        assertTrue(store.saveCatalogChecked(v2));
        assertTrue(Files.isRegularFile(AtomicJsonFiles.bakPath(TitlePaths.catalogFile())));

        store.reset();
        Files.writeString(TitlePaths.catalogFile(), "{truncated", StandardCharsets.UTF_8);

        TitleCatalog recovered = store.loadCatalog();
        assertFalse(store.isCatalogCorrupt());
        assertEquals(1, recovered.titles.size());
        assertEquals("founder", recovered.titles.getFirst().id);
        assertTrue(Files.isRegularFile(TitlePaths.catalogFile()));
        assertTrue(store.saveCatalogChecked(v2));
    }

    @Test
    void playerQuitEvictsCleanCacheAndReceiptsCapAt256() {
        LocalTitleStore store = LocalTitleStore.get();
        UUID uuid = UUID.randomUUID();

        for (int i = 0; i < 300; i++) {
            assertTrue(store.grantOnce(uuid, "receipt-" + i, List.of("§a[称号" + (i % 5) + "]")));
        }

        assertTrue(store.isPlayerCachedForTest(uuid));
        PlayerTitleData inMem = store.loadPlayer(uuid);
        assertEquals(PlayerTitleData.REWARD_RECEIPTS_CAP, inMem.rewardReceipts.size());
        assertFalse(inMem.rewardReceipts.contains("receipt-0"));
        assertTrue(inMem.rewardReceipts.contains("receipt-299"));

        store.onPlayerQuit(uuid);
        assertFalse(store.isPlayerCachedForTest(uuid), "onPlayerQuit must evict clean player title cache");

        PlayerTitleData fromDisk = store.loadPlayer(uuid);
        assertEquals(PlayerTitleData.REWARD_RECEIPTS_CAP, fromDisk.rewardReceipts.size());
        assertTrue(fromDisk.rewardReceipts.contains("receipt-299"));
    }
}
