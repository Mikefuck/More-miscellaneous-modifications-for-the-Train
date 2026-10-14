package com.habitrain.lottery.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.exmo.sre.record.MatchRecord;
import net.exmo.sre.record.MatchRecordStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * World-local match records: matches/*.json + index.jsonl (newest last in file; list newest first).
 */
public final class LocalMatchRecordStore {
    public static final int MAX_RECORDS = 200;
    static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;
    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    private static volatile ExecutorService saveExecutor = createExecutor();

    private LocalMatchRecordStore() {
    }

    public static boolean isReady() {
        return WorldLotteryPaths.ready();
    }

    public static boolean save(MatchRecord record) {
        return save(isReady() ? WorldLotteryPaths.root() : null, record);
    }

    public static boolean save(Path lotteryRoot, MatchRecord record) {
        synchronized (LOCK) {
            if (lotteryRoot == null || record == null || record.matchId == null || record.matchId.isBlank()) {
                return false;
            }
            Path matchPath = MetaFeaturePaths.matchFile(lotteryRoot, record.matchId);
            if (matchPath == null) {
                HabiLotteryMod.LOGGER.warn("Rejected unsafe matchId {}", record.matchId);
                return false;
            }
            try {
                Files.createDirectories(matchesDir(lotteryRoot));
                if (!AtomicJsonFiles.writeString(matchPath, record.toJson(), StandardCharsets.UTF_8, false, false)) {
                    return false;
                }
                if (!appendIndexLine(lotteryRoot, GSON.toJson(record.toSummary()))) {
                    return false;
                }
                return pruneIfNeeded(lotteryRoot);
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.warn("Failed saving match record {}: {}", record.matchId, e.toString());
                return false;
            }
        }
    }

    public static CompletableFuture<Boolean> saveAsync(MatchRecord record) {
        return saveAsync(isReady() ? WorldLotteryPaths.root() : null, record);
    }

    public static CompletableFuture<Boolean> saveAsync(Path lotteryRoot, MatchRecord record) {
        try {
            return CompletableFuture.supplyAsync(() -> save(lotteryRoot, record), executor());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    public static MatchRecordStore.MatchPage listWindow(int offset, int limit) {
        return listWindow(isReady() ? WorldLotteryPaths.root() : null, offset, limit);
    }

    public static MatchRecordStore.MatchPage listWindow(Path lotteryRoot, int offset, int limit) {
        synchronized (LOCK) {
            int safeOffset = Math.max(0, offset);
            int clamped = limit <= 0 ? 50 : Math.min(limit, 200);
            List<MatchRecord.Summary> all = readAllSummariesNewestFirstUnlocked(lotteryRoot);
            int total = all.size();
            if (safeOffset >= total) {
                return new MatchRecordStore.MatchPage(total, safeOffset, new ArrayList<>());
            }
            int end = Math.min(total, safeOffset + clamped);
            return new MatchRecordStore.MatchPage(total, safeOffset, new ArrayList<>(all.subList(safeOffset, end)));
        }
    }

    public static CompletableFuture<MatchRecordStore.MatchPage> listWindowAsync(Path lotteryRoot, int offset, int limit) {
        try {
            return CompletableFuture.supplyAsync(() -> listWindow(lotteryRoot, offset, limit), executor());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(
                    new MatchRecordStore.MatchPage(0, Math.max(0, offset), new ArrayList<>()));
        }
    }

    public static Optional<MatchRecord> load(String matchId) {
        if (!isReady()) {
            return Optional.empty();
        }
        return load(WorldLotteryPaths.root(), matchId);
    }

    public static Optional<MatchRecord> load(Path lotteryRoot, String matchId) {
        synchronized (LOCK) {
            if (lotteryRoot == null || matchId == null || matchId.isBlank()) {
                return Optional.empty();
            }
            Path path = MetaFeaturePaths.matchFile(lotteryRoot, matchId);
            if (path == null || !Files.isRegularFile(path)) {
                return Optional.empty();
            }
            try {
                MatchRecord record = MatchRecord.fromJson(Files.readString(path, StandardCharsets.UTF_8));
                return record == null ? Optional.empty() : Optional.of(record);
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.warn("Failed loading match {}: {}", matchId, e.toString());
                return Optional.empty();
            }
        }
    }

    public static CompletableFuture<Optional<MatchRecord>> loadAsync(Path lotteryRoot, String matchId) {
        try {
            return CompletableFuture.supplyAsync(() -> load(lotteryRoot, matchId), executor());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /** Test/helper: read all summaries newest first. */
    public static List<MatchRecord.Summary> readAllSummariesNewestFirst() {
        if (!isReady()) {
            return new ArrayList<>();
        }
        return readAllSummariesNewestFirst(WorldLotteryPaths.root());
    }

    public static List<MatchRecord.Summary> readAllSummariesNewestFirst(Path lotteryRoot) {
        synchronized (LOCK) {
            return readAllSummariesNewestFirstUnlocked(lotteryRoot);
        }
    }

    /**
     * Wait for in-flight executor saves, then shut the saver down. Parent should call this on
     * {@code SERVER_STOPPING}. A later {@code saveAsync} recreates the executor.
     */
    public static void shutdownAndAwait() {
        shutdownAndAwait(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public static void shutdownAndAwait(long timeout, TimeUnit unit) {
        ExecutorService ex;
        synchronized (LOCK) {
            ex = saveExecutor;
        }
        if (ex == null) {
            return;
        }
        ex.shutdown();
        long wait = timeout <= 0 ? SHUTDOWN_TIMEOUT_SECONDS : timeout;
        TimeUnit timeUnit = unit == null ? TimeUnit.SECONDS : unit;
        try {
            if (!ex.awaitTermination(wait, timeUnit)) {
                HabiLotteryMod.LOGGER.warn(
                        "Match record executor still running after {} {}; interrupting", wait, timeUnit);
                ex.shutdownNow();
                ex.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static List<MatchRecord.Summary> readAllSummariesNewestFirstUnlocked(Path lotteryRoot) {
        List<MatchRecord.Summary> list = new ArrayList<>();
        if (lotteryRoot == null) {
            return list;
        }
        Path index = recordsIndex(lotteryRoot);
        if (!Files.isRegularFile(index)) {
            return list;
        }
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    MatchRecord.Summary summary = MatchRecord.summaryFromJson(line.trim());
                    if (summary == null || MetaFeaturePaths.matchFile(lotteryRoot, summary.matchId) == null)
                        throw new IllegalArgumentException("Invalid record index entry");
                    list.add(summary);
                } catch (RuntimeException invalid) {
                    HabiLotteryMod.LOGGER.warn("Skipping damaged match index row at {}: {}", index, invalid.toString());
                }
            }
        } catch (IOException e) {
            HabiLotteryMod.LOGGER.warn("Failed reading records index: {}", e.toString());
        }
        list.sort(Comparator.comparingLong((MatchRecord.Summary s) -> s.createdAt).reversed());
        return dedupeKeepFirst(list);
    }

    private static List<MatchRecord.Summary> dedupeKeepFirst(List<MatchRecord.Summary> sortedNewestFirst) {
        List<MatchRecord.Summary> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (MatchRecord.Summary s : sortedNewestFirst) {
            if (seen.add(s.matchId)) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean appendIndexLine(Path lotteryRoot, String jsonLine) throws IOException {
        Path index = recordsIndex(lotteryRoot);
        String existing = Files.isRegularFile(index)
                ? Files.readString(index, StandardCharsets.UTF_8)
                : "";
        if (!existing.isEmpty() && !existing.endsWith("\n")) {
            existing = existing + System.lineSeparator();
        }
        String next = existing + jsonLine + System.lineSeparator();
        return AtomicJsonFiles.writeString(index, next, StandardCharsets.UTF_8, false, false);
    }

    private static boolean pruneIfNeeded(Path lotteryRoot) {
        if (lotteryRoot == null) return false;
        List<MatchRecord.Summary> all = readAllSummariesNewestFirstUnlocked(lotteryRoot);
        if (all.size() <= MAX_RECORDS) return true;
        List<MatchRecord.Summary> keep = all.subList(0, MAX_RECORDS);
        List<MatchRecord.Summary> drop = all.subList(MAX_RECORDS, all.size());
        if (!writeIndex(lotteryRoot, keep)) return false;
        // A stale extra file is recoverable. An index pointing at deleted data is not.
        for (MatchRecord.Summary summary : drop) {
            Path file = MetaFeaturePaths.matchFile(lotteryRoot, summary.matchId);
            if (file == null) continue;
            try { Files.deleteIfExists(file); }
            catch (IOException failure) { HabiLotteryMod.LOGGER.warn("Could not remove unindexed record {}", file, failure); }
        }
        return true;
    }

    private static boolean writeIndex(Path root, List<MatchRecord.Summary> summaries) {
        List<MatchRecord.Summary> ordered = new ArrayList<>(summaries);
        ordered.sort(Comparator.comparingLong(s -> s.createdAt));
        String body = ordered.stream().map(GSON::toJson).collect(Collectors.joining(System.lineSeparator()));
        return AtomicJsonFiles.writeString(recordsIndex(root), body.isEmpty() ? body : body + System.lineSeparator(),
                StandardCharsets.UTF_8, true, true);
    }

    /** Rebuild the derived index from intact record entities on world startup. */
    public static boolean repairIndex(Path root) {
        synchronized (LOCK) {
            if (root == null || !Files.isDirectory(matchesDir(root))) return true;
            List<MatchRecord.Summary> summaries = new ArrayList<>();
            try (var files = Files.list(matchesDir(root))) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        MatchRecord record = MatchRecord.fromJson(Files.readString(file, StandardCharsets.UTF_8));
                        if (record != null && file.equals(MetaFeaturePaths.matchFile(root, record.matchId)))
                            summaries.add(record.toSummary());
                    } catch (RuntimeException | IOException invalid) {
                        HabiLotteryMod.LOGGER.warn("Skipping damaged match entity {}", file, invalid);
                    }
                }
            } catch (IOException failure) {
                HabiLotteryMod.LOGGER.error("Cannot rebuild match index", failure);
                return false;
            }
            summaries.sort(Comparator.comparingLong((MatchRecord.Summary summary) -> summary.createdAt).reversed());
            summaries = dedupeKeepFirst(summaries);
            if (!writeIndex(root, summaries)) return false;
            return pruneIfNeeded(root);
        }
    }

    private static Path recordsDir(Path lotteryRoot) {
        return lotteryRoot.resolve("records");
    }

    private static Path matchesDir(Path lotteryRoot) {
        return recordsDir(lotteryRoot).resolve("matches");
    }

    private static Path recordsIndex(Path lotteryRoot) {
        return recordsDir(lotteryRoot).resolve("index.jsonl");
    }

    private static ExecutorService executor() {
        ExecutorService ex = saveExecutor;
        if (ex != null && !ex.isShutdown()) {
            return ex;
        }
        synchronized (LOCK) {
            ex = saveExecutor;
            if (ex == null || ex.isShutdown()) {
                saveExecutor = createExecutor();
                return saveExecutor;
            }
            return ex;
        }
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "habitrain-lottery-match-records");
            thread.setDaemon(true);
            return thread;
        });
    }
}
