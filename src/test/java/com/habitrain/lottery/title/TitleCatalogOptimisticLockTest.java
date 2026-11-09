package com.habitrain.lottery.title;

import com.google.gson.Gson;
import com.habitrain.lottery.network.LotteryTitleServerHandlers;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code I-10} and {@code B-01}:
 * <ul>
 *   <li>Legacy {@code catalog.json} without {@code revision} defaults to {@code 0L}.</li>
 *   <li>{@link LotteryTitleServerHandlers#saveCatalogOptimistically} requires
 *       {@code expectedRevision == current.revision} and increments {@code revision} on save.</li>
 *   <li>{@link TitleCatalog} enforces max size ({@code <= 512}), ID ({@code <= 64}), and display
 *       text ({@code <= 256}) bounds.</li>
 *   <li>Corrupt {@code catalog.json} locks catalog saving via {@link LocalTitleStore#isCatalogCorrupt()}.</li>
 * </ul>
 */
class TitleCatalogOptimisticLockTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        WorldLotteryPaths.initForTest(tempDir);
        TitlePaths.ensureDirs();
        LocalTitleStore.get().reset();
    }

    @AfterEach
    void tearDown() {
        LocalTitleStore.get().reset();
        WorldLotteryPaths.reset();
    }

    @Test
    void legacyJsonDefaultsRevisionZeroAndOptimisticLockPreventsLostUpdates() throws Exception {
        // Legacy catalog.json written before v1.1.64 (no "revision" field)
        String legacyJson = "{\"version\":1,\"titles\":[{\"id\":\"legacy\",\"display\":\"§a元老\",\"enabled\":true}]}";
        Files.writeString(TitlePaths.catalogFile(), legacyJson, StandardCharsets.UTF_8);
        LocalTitleStore.get().reset();

        TitleCatalog loaded = LocalTitleStore.get().loadCatalog();
        assertEquals(0L, loaded.revision, "Legacy JSON without revision must default to 0");
        assertEquals(1, loaded.titles.size());

        // First admin saves against expectedRevision = 0 -> succeeds and advances revision to 1
        TitleCatalog firstEdit = new TitleCatalog();
        firstEdit.titles.add(new TitleCatalog.TitleEntry("legacy", "§a元老", true));
        firstEdit.titles.add(new TitleCatalog.TitleEntry("mvp", "§6全场最佳", true));

        LotteryTitleServerHandlers.SaveOutcome save1 =
                LotteryTitleServerHandlers.saveCatalogOptimistically(GSON.toJson(firstEdit), 0L);
        assertTrue(save1.ok(), save1.message());
        assertNotNull(save1.savedCatalog());
        assertEquals(1L, save1.savedCatalog().revision);
        assertEquals(1L, LocalTitleStore.get().loadCatalog().revision);

        // Concurrent stale admin attempts to save against old expectedRevision = 0 -> rejected!
        TitleCatalog staleEdit = new TitleCatalog();
        staleEdit.titles.add(new TitleCatalog.TitleEntry("stale_only", "§c覆盖数据", true));
        LotteryTitleServerHandlers.SaveOutcome conflict =
                LotteryTitleServerHandlers.saveCatalogOptimistically(GSON.toJson(staleEdit), 0L);
        assertFalse(conflict.ok());
        assertEquals(LotteryTitleServerHandlers.REVISION_CONFLICT_MESSAGE, conflict.message());

        // Verify disk still preserves firstEdit (2 entries, revision = 1)
        TitleCatalog afterConflict = LocalTitleStore.get().loadCatalog();
        assertEquals(1L, afterConflict.revision);
        assertEquals(2, afterConflict.titles.size());

        // Saving with fresh expectedRevision = 1 advances revision to 2
        LotteryTitleServerHandlers.SaveOutcome save2 =
                LotteryTitleServerHandlers.saveCatalogOptimistically(GSON.toJson(firstEdit), 1L);
        assertTrue(save2.ok(), save2.message());
        assertEquals(2L, save2.savedCatalog().revision);
    }

    @Test
    void catalogBoundsRejectOversizedEntriesAndDuplicates() {
        TitleCatalog tooMany = new TitleCatalog();
        for (int i = 0; i <= TitleCatalog.MAX_TITLES; i++) {
            tooMany.titles.add(new TitleCatalog.TitleEntry("id_" + i, "Title " + i, true));
        }
        assertNotNull(tooMany.validateAndNormalize(), "513 titles must exceed MAX_TITLES (512)");

        TitleCatalog longId = new TitleCatalog();
        longId.titles.add(new TitleCatalog.TitleEntry("k".repeat(65), "Valid Display", true));
        assertNotNull(longId.validateAndNormalize(), "65-char ID must exceed MAX_ID_LENGTH (64)");

        TitleCatalog longDisplay = new TitleCatalog();
        longDisplay.titles.add(new TitleCatalog.TitleEntry("valid_id", "展示".repeat(129), true));
        assertNotNull(longDisplay.validateAndNormalize(), "258-char display must exceed MAX_DISPLAY_LENGTH (256)");

        TitleCatalog duplicateId = new TitleCatalog();
        duplicateId.titles.add(new TitleCatalog.TitleEntry("dup", "A", true));
        duplicateId.titles.add(new TitleCatalog.TitleEntry("dup", "B", true));
        assertNotNull(duplicateId.validateAndNormalize(), "Duplicate IDs must be rejected");

        TitleCatalog validBoundary = new TitleCatalog();
        validBoundary.titles.add(new TitleCatalog.TitleEntry("k".repeat(64), "x".repeat(256), true));
        assertNull(validBoundary.validateAndNormalize());
    }

    @Test
    void corruptCatalogBlocksSaveInsteadOfOverwritingDisk() throws Exception {
        String truncatedJson = "{\"version\":1,\"titles\":[{\"id\":\"precious\"";
        Files.writeString(TitlePaths.catalogFile(), truncatedJson, StandardCharsets.UTF_8);
        LocalTitleStore.get().reset();

        TitleCatalog overwriteAttempt = new TitleCatalog();
        overwriteAttempt.titles.add(new TitleCatalog.TitleEntry("new_entry", "§e新称号", true));

        LotteryTitleServerHandlers.SaveOutcome outcome =
                LotteryTitleServerHandlers.saveCatalogOptimistically(GSON.toJson(overwriteAttempt), 0L);

        assertFalse(outcome.ok());
        assertTrue(LocalTitleStore.get().isCatalogCorrupt());
        assertEquals(LotteryTitleServerHandlers.CORRUPT_CATALOG_MESSAGE, outcome.message());
    }
}
