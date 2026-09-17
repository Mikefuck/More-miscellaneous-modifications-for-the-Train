package com.habitrain.lottery.record;

import com.google.gson.Gson;
import net.exmo.sre.record.MatchRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit test for newest-first + dedupe logic used by LocalMatchRecordStore index.
 */
class LocalMatchRecordIndexTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path temp;

    @Test
    void newestFirstAndDedupe() throws Exception {
        Path index = temp.resolve("index.jsonl");
        List<MatchRecord.Summary> rows = new ArrayList<>();
        rows.add(summary("a", 100));
        rows.add(summary("b", 200));
        rows.add(summary("c", 300));
        rows.add(summary("b", 250)); // newer duplicate of b
        StringBuilder sb = new StringBuilder();
        for (MatchRecord.Summary s : rows) {
            sb.append(GSON.toJson(s)).append('\n');
        }
        Files.writeString(index, sb.toString(), StandardCharsets.UTF_8);

        List<MatchRecord.Summary> parsed = new ArrayList<>();
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            parsed.add(GSON.fromJson(line, MatchRecord.Summary.class));
        }
        parsed.sort(Comparator.comparingLong((MatchRecord.Summary s) -> s.createdAt).reversed());
        List<MatchRecord.Summary> deduped = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (MatchRecord.Summary s : parsed) {
            if (seen.add(s.matchId)) {
                deduped.add(s);
            }
        }
        assertEquals(3, deduped.size());
        assertEquals("c", deduped.get(0).matchId);
        assertEquals("b", deduped.get(1).matchId);
        assertEquals(250, deduped.get(1).createdAt);
        assertEquals("a", deduped.get(2).matchId);

        // window 0,2
        List<MatchRecord.Summary> page = deduped.subList(0, 2);
        assertEquals(2, page.size());
        assertEquals("c", page.get(0).matchId);
        assertEquals("b", page.get(1).matchId);
    }

    private static MatchRecord.Summary summary(String id, long createdAt) {
        MatchRecord.Summary s = new MatchRecord.Summary();
        s.matchId = id;
        s.createdAt = createdAt;
        s.playerCount = 1;
        return s;
    }
}
