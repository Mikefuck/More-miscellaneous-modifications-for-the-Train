package com.habitrain.lottery.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * End-to-end UTF-8 byte-aware &amp; surrogate-safe chunked JSON transfer engine ({@code B-10}).
 *
 * <p>Guarantees that every C2S chunk stays strictly within vanilla Minecraft 1.21.1's
 * {@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} ({@code 32,767} bytes) and every S2C
 * chunk stays well within {@code 65,536} bytes, while enforcing authorization and rate-limiting
 * before buffering any server-side C2S chunk state.
 */
public final class ChunkedJsonTransfer {
    /** Sentinel {@code transferId} marking an unsplit logical payload prior to wire transmission. */
    public static final UUID LOGICAL_TRANSFER = new UUID(0L, 0L);

    /** Per-chunk C2S UTF-16 char cap (worst-case 3-byte UTF-8 = 24,576 bytes &lt; 32,767 bytes). */
    public static final int C2S_CHUNK_CHARS = 8_192;
    /** Per-chunk C2S UTF-8 byte budget leaving &gt; 8 KB headroom for headers &amp; metadata. */
    public static final int C2S_CHUNK_MAX_UTF8_BYTES = 24_000;
    public static final int C2S_MAX_CHUNKS = 128;
    public static final int C2S_MAX_TOTAL_UTF8_BYTES = 1_048_576;

    /** Per-chunk S2C UTF-16 char cap (worst-case 3-byte UTF-8 = 49,152 bytes &lt; 65,536 bytes). */
    public static final int S2C_CHUNK_CHARS = 16_384;
    /** Per-chunk S2C UTF-8 byte budget leaving &gt; 16 KB headroom for headers &amp; metadata. */
    public static final int S2C_CHUNK_MAX_UTF8_BYTES = 48_000;
    public static final int S2C_MAX_CHUNKS = 256;
    public static final int S2C_MAX_TOTAL_UTF8_BYTES = 4_194_304;

    public static final long TRANSFER_TIMEOUT_MS = 30_000L;

    @FunctionalInterface
    public interface ChunkFactory<P> {
        P create(String jsonChunk, UUID transferId, int chunkIndex, int chunkCount);
    }

    public enum GateStatus {
        ACCEPTED_PARTIAL,
        COMPLETED,
        UNAUTHORIZED,
        RATE_LIMITED,
        INVALID_CHUNK
    }

    public record GateResult<M>(GateStatus status, String json, M metadata) {
    }

    public record Completed<M>(String json, M metadata) {
    }

    private record PlayerChannelKey(UUID playerId, String channel) {
    }

    private static final Map<PlayerChannelKey, Assembler<Object>> IN_FLIGHT_C2S = new ConcurrentHashMap<>();

    private ChunkedJsonTransfer() {
    }

    /** Allocation-free exact UTF-8 byte length calculation (replaces unpaired surrogates with 1 byte '?' as Netty/JDK UTF-8 does, or 3 bytes conservatively). */
    public static int utf8Length(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        int len = text.length();
        for (int i = 0; i < len; ) {
            char c = text.charAt(i);
            if (c <= 0x7F) {
                total += 1;
                i += 1;
            } else if (c <= 0x7FF) {
                total += 2;
                i += 1;
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
                total += 4;
                i += 2;
            } else {
                // 3-byte BMP char or unpaired surrogate (conservatively counted as 3 bytes)
                total += 3;
                i += 1;
            }
        }
        return total;
    }

    public static <P> List<P> splitC2S(String fullJson, ChunkFactory<P> factory) {
        return split(
                fullJson,
                C2S_CHUNK_CHARS,
                C2S_CHUNK_MAX_UTF8_BYTES,
                C2S_MAX_CHUNKS,
                C2S_MAX_TOTAL_UTF8_BYTES,
                factory);
    }

    public static <P> List<P> splitS2C(String fullJson, ChunkFactory<P> factory) {
        return split(
                fullJson,
                S2C_CHUNK_CHARS,
                S2C_CHUNK_MAX_UTF8_BYTES,
                S2C_MAX_CHUNKS,
                S2C_MAX_TOTAL_UTF8_BYTES,
                factory);
    }

    public static <P> List<P> split(
            String fullJson,
            int maxChunkChars,
            int maxChunkUtf8Bytes,
            int maxChunks,
            int maxTotalUtf8Bytes,
            ChunkFactory<P> factory
    ) {
        Objects.requireNonNull(factory, "factory");
        String safe = fullJson == null ? "" : fullJson;
        int totalBytes = utf8Length(safe);
        if (totalBytes > maxTotalUtf8Bytes) {
            throw new IllegalArgumentException(
                    "JSON payload exceeds max total UTF-8 budget (" + totalBytes + " > " + maxTotalUtf8Bytes + ")");
        }
        List<String> pieces = splitPieces(safe, maxChunkChars, maxChunkUtf8Bytes);
        if (pieces.size() > maxChunks) {
            throw new IllegalArgumentException(
                    "JSON payload exceeds max chunk count (" + pieces.size() + " > " + maxChunks + ")");
        }
        UUID transferId = UUID.randomUUID();
        if (LOGICAL_TRANSFER.equals(transferId)) {
            transferId = new UUID(1L, 1L);
        }
        int count = pieces.size();
        List<P> packets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            packets.add(factory.create(pieces.get(i), transferId, i, count));
        }
        return List.copyOf(packets);
    }

    static List<String> splitPieces(String safe, int maxChunkChars, int maxChunkUtf8Bytes) {
        if (safe.isEmpty()) {
            return List.of("");
        }
        List<String> pieces = new ArrayList<>();
        int len = safe.length();
        int start = 0;
        while (start < len) {
            int idx = start;
            int chars = 0;
            int bytes = 0;
            while (idx < len) {
                char c = safe.charAt(idx);
                int charUnits;
                int cpBytes;
                if (c <= 0x7F) {
                    charUnits = 1;
                    cpBytes = 1;
                } else if (c <= 0x7FF) {
                    charUnits = 1;
                    cpBytes = 2;
                } else if (Character.isHighSurrogate(c) && idx + 1 < len && Character.isLowSurrogate(safe.charAt(idx + 1))) {
                    charUnits = 2;
                    cpBytes = 4;
                } else {
                    charUnits = 1;
                    cpBytes = 3;
                }
                if (chars > 0 && (chars + charUnits > maxChunkChars || bytes + cpBytes > maxChunkUtf8Bytes)) {
                    break;
                }
                idx += charUnits;
                chars += charUnits;
                bytes += cpBytes;
            }
            pieces.add(safe.substring(start, idx));
            start = idx;
        }
        return pieces;
    }

    public static <M> Assembler<M> newClientS2CAssembler() {
        return new Assembler<>(
                S2C_CHUNK_CHARS,
                S2C_CHUNK_MAX_UTF8_BYTES,
                S2C_MAX_CHUNKS,
                S2C_MAX_TOTAL_UTF8_BYTES,
                TRANSFER_TIMEOUT_MS);
    }

    public static <M> Assembler<M> newServerC2SAssembler() {
        return new Assembler<>(
                C2S_CHUNK_CHARS,
                C2S_CHUNK_MAX_UTF8_BYTES,
                C2S_MAX_CHUNKS,
                C2S_MAX_TOTAL_UTF8_BYTES,
                TRANSFER_TIMEOUT_MS);
    }

    /**
     * Authorizes, rate-limits (strictly on {@code chunkIndex == 0}), bounds, and reassembles
     * multi-chunk C2S admin saves on the server thread.
     */
    public static <M> GateResult<M> acceptAuthorizedC2S(
            UUID playerId,
            String channel,
            boolean authorized,
            BooleanSupplier rateLimitedOnStart,
            String chunkJson,
            UUID transferId,
            int chunkIndex,
            int chunkCount,
            M metadata
    ) {
        return acceptAuthorizedC2S(
                playerId,
                channel,
                authorized,
                rateLimitedOnStart,
                chunkJson,
                transferId,
                chunkIndex,
                chunkCount,
                metadata,
                System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    public static <M> GateResult<M> acceptAuthorizedC2S(
            UUID playerId,
            String channel,
            boolean authorized,
            BooleanSupplier rateLimitedOnStart,
            String chunkJson,
            UUID transferId,
            int chunkIndex,
            int chunkCount,
            M metadata,
            long nowMs
    ) {
        if (playerId == null || channel == null || channel.isBlank()) {
            return new GateResult<>(GateStatus.INVALID_CHUNK, null, metadata);
        }
        PlayerChannelKey key = new PlayerChannelKey(playerId, channel);
        if (!authorized) {
            IN_FLIGHT_C2S.remove(key);
            return new GateResult<>(
                    chunkIndex == 0 ? GateStatus.UNAUTHORIZED : GateStatus.INVALID_CHUNK,
                    null,
                    metadata);
        }
        if (chunkJson == null
                || transferId == null
                || chunkCount < 1
                || chunkCount > C2S_MAX_CHUNKS
                || chunkIndex < 0
                || chunkIndex >= chunkCount
                || chunkJson.length() > C2S_CHUNK_CHARS) {
            IN_FLIGHT_C2S.remove(key);
            return new GateResult<>(GateStatus.INVALID_CHUNK, null, metadata);
        }
        int chunkBytes = utf8Length(chunkJson);
        if (chunkBytes > C2S_CHUNK_MAX_UTF8_BYTES || chunkBytes > C2S_MAX_TOTAL_UTF8_BYTES) {
            IN_FLIGHT_C2S.remove(key);
            return new GateResult<>(GateStatus.INVALID_CHUNK, null, metadata);
        }

        if (chunkIndex == 0) {
            IN_FLIGHT_C2S.remove(key);
            if (rateLimitedOnStart != null && rateLimitedOnStart.getAsBoolean()) {
                return new GateResult<>(GateStatus.RATE_LIMITED, null, metadata);
            }
            if (chunkCount == 1) {
                return new GateResult<>(GateStatus.COMPLETED, chunkJson, metadata);
            }
            Assembler<Object> fresh = newServerC2SAssembler();
            Completed<Object> first = fresh.accept(chunkJson, transferId, 0, chunkCount, metadata, nowMs);
            if (first != null) {
                return new GateResult<>(GateStatus.COMPLETED, first.json(), (M) first.metadata());
            }
            if (!fresh.hasActiveTransfer()) {
                return new GateResult<>(GateStatus.INVALID_CHUNK, null, metadata);
            }
            IN_FLIGHT_C2S.put(key, fresh);
            return new GateResult<>(GateStatus.ACCEPTED_PARTIAL, null, metadata);
        }

        Assembler<Object> active = IN_FLIGHT_C2S.get(key);
        if (active == null) {
            return new GateResult<>(GateStatus.INVALID_CHUNK, null, metadata);
        }
        synchronized (active) {
            Completed<Object> done = active.accept(
                    chunkJson, transferId, chunkIndex, chunkCount, metadata, nowMs);
            if (done != null) {
                IN_FLIGHT_C2S.remove(key, active);
                return new GateResult<>(GateStatus.COMPLETED, done.json(), (M) done.metadata());
            }
            if (!active.hasActiveTransfer()) {
                IN_FLIGHT_C2S.remove(key, active);
                return new GateResult<>(GateStatus.INVALID_CHUNK, null, metadata);
            }
            return new GateResult<>(GateStatus.ACCEPTED_PARTIAL, null, metadata);
        }
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        IN_FLIGHT_C2S.keySet().removeIf(k -> playerId.equals(k.playerId()));
    }

    public static void clearAll() {
        IN_FLIGHT_C2S.clear();
    }

    public static int activeServerTransfersForTest() {
        return IN_FLIGHT_C2S.size();
    }

    /** Ordered single-stream chunk reassembler; publishes only when all {@code 0..chunkCount-1} chunks arrive. */
    public static final class Assembler<M> {
        private final int maxChunkChars;
        private final int maxChunkUtf8Bytes;
        private final int maxChunks;
        private final int maxTotalUtf8Bytes;
        private final long timeoutMs;

        private UUID transferId;
        private StringBuilder json;
        private int expectedNext;
        private int chunkCount;
        private int accumulatedUtf8Bytes;
        private long startedAtMs;
        private M metadata;

        public Assembler(
                int maxChunkChars,
                int maxChunkUtf8Bytes,
                int maxChunks,
                int maxTotalUtf8Bytes,
                long timeoutMs
        ) {
            this.maxChunkChars = maxChunkChars;
            this.maxChunkUtf8Bytes = maxChunkUtf8Bytes;
            this.maxChunks = maxChunks;
            this.maxTotalUtf8Bytes = maxTotalUtf8Bytes;
            this.timeoutMs = timeoutMs;
        }

        public synchronized void reset() {
            transferId = null;
            json = null;
            expectedNext = 0;
            chunkCount = 0;
            accumulatedUtf8Bytes = 0;
            startedAtMs = 0L;
            metadata = null;
        }

        public synchronized boolean hasActiveTransfer() {
            return transferId != null && json != null && expectedNext < chunkCount;
        }

        public synchronized Completed<M> accept(
                String chunkJson,
                UUID incomingTransferId,
                int chunkIndex,
                int totalChunks,
                M incomingMetadata
        ) {
            return accept(
                    chunkJson,
                    incomingTransferId,
                    chunkIndex,
                    totalChunks,
                    incomingMetadata,
                    System.currentTimeMillis());
        }

        public synchronized Completed<M> accept(
                String chunkJson,
                UUID incomingTransferId,
                int chunkIndex,
                int totalChunks,
                M incomingMetadata,
                long nowMs
        ) {
            if (chunkJson == null
                    || incomingTransferId == null
                    || totalChunks < 1
                    || totalChunks > maxChunks
                    || chunkIndex < 0
                    || chunkIndex >= totalChunks
                    || chunkJson.length() > maxChunkChars) {
                reset();
                return null;
            }
            int chunkBytes = utf8Length(chunkJson);
            if (chunkBytes > maxChunkUtf8Bytes) {
                reset();
                return null;
            }
            if (chunkIndex == 0) {
                reset();
                if (chunkBytes > maxTotalUtf8Bytes) {
                    return null;
                }
                if (totalChunks == 1) {
                    return new Completed<>(chunkJson, incomingMetadata);
                }
                transferId = incomingTransferId;
                json = new StringBuilder(Math.min(maxTotalUtf8Bytes, chunkJson.length() * Math.min(totalChunks, 8)));
                json.append(chunkJson);
                expectedNext = 1;
                chunkCount = totalChunks;
                accumulatedUtf8Bytes = chunkBytes;
                startedAtMs = nowMs;
                metadata = incomingMetadata;
                return null;
            }
            if (transferId == null
                    || json == null
                    || !transferId.equals(incomingTransferId)
                    || chunkIndex != expectedNext
                    || totalChunks != chunkCount
                    || !Objects.deepEquals(metadata, incomingMetadata)
                    || (timeoutMs > 0L && nowMs - startedAtMs > timeoutMs)
                    || accumulatedUtf8Bytes + chunkBytes > maxTotalUtf8Bytes) {
                reset();
                return null;
            }
            json.append(chunkJson);
            accumulatedUtf8Bytes += chunkBytes;
            expectedNext++;
            if (expectedNext != chunkCount) {
                return null;
            }
            Completed<M> completed = new Completed<>(json.toString(), metadata);
            reset();
            return completed;
        }
    }
}
