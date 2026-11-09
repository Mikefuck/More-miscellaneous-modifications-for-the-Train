package com.habitrain.lottery.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded wire chunks preserve every row of a logical title page, even for large inventories. */
public final class TitlePageTransfer {
    public static final int CHUNK_CHARS = ChunkedJsonTransfer.S2C_CHUNK_CHARS;
    public static final int MAX_CHUNKS = 1_024;
    public static final UUID LOGICAL_PAGE = ChunkedJsonTransfer.LOGICAL_TRANSFER;

    private TitlePageTransfer() {
    }

    public static List<TitlePlayerPageS2C> chunks(TitlePlayerPageS2C page) {
        String json = page.playersJson() == null ? "{}" : page.playersJson();
        if (json.length() > CHUNK_CHARS * MAX_CHUNKS) {
            throw new IllegalArgumentException("Title page exceeds transfer limit");
        }
        List<String> pieces = ChunkedJsonTransfer.splitPieces(
                json, CHUNK_CHARS, ChunkedJsonTransfer.S2C_CHUNK_MAX_UTF8_BYTES);
        if (pieces.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("Title page exceeds transfer chunk limit");
        }
        UUID transfer = UUID.randomUUID();
        if (LOGICAL_PAGE.equals(transfer)) {
            transfer = new UUID(1L, 1L);
        }
        List<TitlePlayerPageS2C> packets = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            packets.add(new TitlePlayerPageS2C(
                    pieces.get(i),
                    page.page(),
                    page.totalPages(),
                    page.totalMatched(),
                    transfer,
                    i,
                    pieces.size()));
        }
        return List.copyOf(packets);
    }

    /** One in-flight response; state is published only after its last ordered chunk. */
    public static final class Assembler {
        private UUID transfer;
        private StringBuilder json;
        private int next;
        private int count;
        private int page;
        private int pages;
        private int matched;

        public synchronized void reset() {
            transfer = null;
            json = null;
            next = 0;
            count = 0;
        }

        public synchronized TitlePlayerPageS2C accept(TitlePlayerPageS2C part) {
            if (part == null || part.playersJson() == null || part.transferId() == null
                    || part.chunkCount() < 1 || part.chunkCount() > MAX_CHUNKS
                    || part.chunkIndex() < 0 || part.chunkIndex() >= part.chunkCount()
                    || part.playersJson().length() > CHUNK_CHARS
                    || ChunkedJsonTransfer.utf8Length(part.playersJson())
                            > ChunkedJsonTransfer.S2C_CHUNK_MAX_UTF8_BYTES) {
                reset();
                return null;
            }
            if (part.chunkIndex() == 0) {
                reset();
                transfer = part.transferId();
                json = new StringBuilder();
                count = part.chunkCount();
                page = part.page();
                pages = part.totalPages();
                matched = part.totalMatched();
            }
            if (!part.transferId().equals(transfer) || part.chunkIndex() != next
                    || part.chunkCount() != count || part.page() != page
                    || part.totalPages() != pages || part.totalMatched() != matched) {
                reset();
                return null;
            }
            json.append(part.playersJson());
            if (++next != count) {
                return null;
            }
            TitlePlayerPageS2C result = new TitlePlayerPageS2C(json.toString(), page, pages, matched);
            reset();
            return result;
        }
    }
}
