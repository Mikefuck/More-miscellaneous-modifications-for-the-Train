package com.habitrain.lottery.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.habitrain.lottery.HabiLotteryMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LotteryHistoryStore {
    private static final Gson GSON = new Gson();
    private static final LotteryHistoryStore INSTANCE = new LotteryHistoryStore();
    static final long ROTATE_MAX_BYTES = 1_048_576L;
    static final int ROTATE_MAX_LINES = 10_000;

    private final Map<UUID, List<String>> pending = new ConcurrentHashMap<>();

    private LotteryHistoryStore() {
    }

    public static LotteryHistoryStore get() {
        return INSTANCE;
    }

    /** Buffer one jsonl row; written when {@link #flushPending(UUID)} runs (player flush / quit). */
    public void append(UUID uuid, int poolId, int quality, String item, String resultType, int coinDelta, int chanceAfter) {
        if (uuid == null || !WorldLotteryPaths.ready()) {
            return;
        }
        JsonObject row = new JsonObject();
        row.addProperty("ts", System.currentTimeMillis());
        row.addProperty("poolId", poolId);
        row.addProperty("quality", quality);
        row.addProperty("item", item == null ? "" : item);
        row.addProperty("result", resultType == null ? "" : resultType);
        row.addProperty("coinDelta", coinDelta);
        row.addProperty("chanceAfter", chanceAfter);
        String line = GSON.toJson(row) + System.lineSeparator();
        pending.compute(uuid, (k, list) -> {
            List<String> out = list == null ? new ArrayList<>() : list;
            out.add(line);
            return out;
        });
    }

    public void flushPending(UUID uuid) {
        if (uuid == null) {
            return;
        }
        List<String> lines = pending.remove(uuid);
        writeLines(uuid, lines);
    }

    public void flushAllPending() {
        for (UUID uuid : List.copyOf(pending.keySet())) {
            flushPending(uuid);
        }
    }

    void resetForTest() {
        pending.clear();
    }

    private static void writeLines(UUID uuid, List<String> lines) {
        if (lines == null || lines.isEmpty() || !WorldLotteryPaths.ready()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line);
        }
        try {
            Files.createDirectories(WorldLotteryPaths.historyDir());
            Path file = WorldLotteryPaths.historyFile(uuid);
            if (file == null) {
                return;
            }
            rotateIfNeeded(file, ROTATE_MAX_BYTES, ROTATE_MAX_LINES);
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            HabiLotteryMod.LOGGER.warn("Failed appending lottery history for {}: {}", uuid, e.toString());
        }
    }

    static void rotateIfNeeded(Path file, long maxBytes, int maxLines) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        long size = Files.size(file);
        boolean overSize = maxBytes > 0 && size >= maxBytes;
        boolean overLines = false;
        if (!overSize && maxLines > 0) {
            overLines = countLines(file) >= maxLines;
        }
        if (!overSize && !overLines) {
            return;
        }
        Path rotated = file.resolveSibling(file.getFileName().toString() + ".1");
        Files.deleteIfExists(rotated);
        Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING);
    }

    private static int countLines(Path file) throws IOException {
        int lines = 0;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                lines++;
            }
        }
        return lines;
    }
}
