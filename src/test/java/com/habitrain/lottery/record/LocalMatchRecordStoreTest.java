package com.habitrain.lottery.record;

import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.exmo.sre.record.MatchRecord;
import net.exmo.sre.record.MatchRecordStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class LocalMatchRecordStoreTest {
    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        WorldLotteryPaths.clear();
    }

    @Test
    void pathOverloadSavesWithoutWorldPathsReady() throws Exception {
        WorldLotteryPaths.clear();
        assertFalse(WorldLotteryPaths.ready());

        Path lotteryRoot = temp.resolve("world-a");
        MatchRecord record = new MatchRecord();
        record.matchId = "m1";
        record.createdAt = 100L;
        record.playerCount = 1;

        assertFalse(LocalMatchRecordStore.save(record));
        assertTrue(LocalMatchRecordStore.save(lotteryRoot, record));
        assertTrue(Files.isRegularFile(lotteryRoot.resolve("records").resolve("matches").resolve("m1.json")));

        Optional<MatchRecord> loaded = LocalMatchRecordStore.load(lotteryRoot, "m1");
        assertTrue(loaded.isPresent());
        assertEquals("m1", loaded.get().matchId);

        MatchRecordStore.MatchPage page = LocalMatchRecordStore.listWindow(lotteryRoot, 0, 10);
        assertEquals(1, page.total());
        assertEquals("m1", page.items().get(0).matchId);

        Path worldB = temp.resolve("world-b");
        WorldLotteryPaths.initForTests(worldB);
        assertTrue(WorldLotteryPaths.ready());
        assertFalse(Files.isRegularFile(worldB.resolve("records").resolve("matches").resolve("m1.json")));
    }

    @Test
    void nullRootIsNoOp() {
        MatchRecord record = new MatchRecord();
        record.matchId = "m1";
        assertFalse(LocalMatchRecordStore.save(null, record));
        assertTrue(LocalMatchRecordStore.load(null, "m1").isEmpty());
        assertEquals(0, LocalMatchRecordStore.listWindow(null, 0, 10).total());
        assertTrue(LocalMatchRecordStore.readAllSummariesNewestFirst(null).isEmpty());
    }

    @Test
    void unsafeMatchIdDoesNotEscapeMatchesDir() throws Exception {
        Path lotteryRoot = temp.resolve("habitrain_lottery");
        Path matches = lotteryRoot.resolve("records").resolve("matches");
        Files.createDirectories(matches);
        WorldLotteryPaths.initForTests(lotteryRoot);

        for (String id : List.of("../x", "..\\x", "a/b", "a\\b")) {
            assertNull(MetaFeaturePaths.matchFile(id), id);
            assertNull(MetaFeaturePaths.matchFile(lotteryRoot, id), id);
            assertNull(MetaFeaturePaths.resolveMatchFile(matches, id), id);
            MatchRecord record = match(id, 1L);
            assertFalse(LocalMatchRecordStore.save(lotteryRoot, record), id);
            assertTrue(LocalMatchRecordStore.load(lotteryRoot, id).isEmpty(), id);
        }

        assertFalse(Files.exists(lotteryRoot.resolve("x.json")));
        assertFalse(Files.exists(lotteryRoot.resolve("records").resolve("x.json")));
        assertFalse(Files.exists(lotteryRoot.resolve("records").resolve("index.jsonl")));
        assertFalse(Files.exists(temp.resolve("x.json")));
        assertFalse(Files.exists(matches.resolve("a")));

        Path valid = MetaFeaturePaths.matchFile(lotteryRoot, "safe-id");
        assertNotNull(valid);
        assertTrue(valid.startsWith(matches.toAbsolutePath().normalize()));
        assertEquals(matches.toAbsolutePath().normalize(), valid.getParent());
    }

    @Test
    void sequentialSavesWriteTwoIndexLinesAndMatchFiles() throws Exception {
        Path lotteryRoot = temp.resolve("world");
        assertTrue(LocalMatchRecordStore.save(lotteryRoot, match("id-a", 100L)));
        assertTrue(LocalMatchRecordStore.save(lotteryRoot, match("id-b", 200L)));
        assertValidIndexAndMatchFiles(lotteryRoot, "id-a", "id-b");
    }

    @Test
    void saveAsyncTwoRecordsStayConsistent() throws Exception {
        Path lotteryRoot = temp.resolve("world-async");
        CompletableFuture<Boolean> first = LocalMatchRecordStore.saveAsync(lotteryRoot, match("id-a", 100L));
        CompletableFuture<Boolean> second = LocalMatchRecordStore.saveAsync(lotteryRoot, match("id-b", 200L));
        assertTrue(first.join());
        assertTrue(second.join());
        assertValidIndexAndMatchFiles(lotteryRoot, "id-a", "id-b");
    }

    private static MatchRecord match(String id, long createdAt) {
        MatchRecord record = new MatchRecord();
        record.matchId = id;
        record.createdAt = createdAt;
        record.playerCount = 1;
        return record;
    }

    private static void assertValidIndexAndMatchFiles(Path lotteryRoot, String... ids) throws Exception {
        Path index = lotteryRoot.resolve("records").resolve("index.jsonl");
        assertTrue(Files.isRegularFile(index));
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8).stream()
                .filter(line -> line != null && !line.isBlank())
                .toList();
        assertEquals(ids.length, lines.size());
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            MatchRecord.Summary summary = MatchRecord.summaryFromJson(line);
            assertNotNull(summary, "index line must be json: " + line);
            assertNotNull(summary.matchId);
            seen.add(summary.matchId);
            assertTrue(Files.isRegularFile(
                    lotteryRoot.resolve("records").resolve("matches").resolve(summary.matchId + ".json")));
        }
        for (String id : ids) {
            assertTrue(seen.contains(id), id);
        }
        assertFalse(Files.exists(index.resolveSibling("index.jsonl.tmp")));
    }
}
