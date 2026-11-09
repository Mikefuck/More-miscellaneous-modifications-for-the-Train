package com.habitrain.lottery.network;

import com.google.gson.JsonParser;
import com.habitrain.lottery.title.PlayerTitleData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TitlePageTransferTest {
    @Test
    void largeInventoriesPreserveEveryPlayerAcrossPagesAndBoundEachWirePacket() {
        List<LotteryAdminServerHandlers.PlayerStub> players = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            players.add(new LotteryAdminServerHandlers.PlayerStub(new UUID(0, i), "p%03d".formatted(i), false));
        }
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < 512; i++) titles.add("礼物🎁".repeat(10) + i);
        int seen = 0;
        for (int pageIndex = 0; pageIndex < 2; pageIndex++) {
            var page = LotteryTitleServerHandlers.buildPaginatedTitlePlayers(players, "", pageIndex, 50, id -> {
                PlayerTitleData data = new PlayerTitleData();
                data.owned = titles;
                return data;
            });
            int expected = pageIndex == 0 ? 50 : 10;
            assertEquals(expected, JsonParser.parseString(page.playersJson()).getAsJsonObject().size());
            TitlePageTransfer.Assembler assembler = new TitlePageTransfer.Assembler();
            TitlePlayerPageS2C complete = null;
            var chunks = TitlePageTransfer.chunks(page);
            assertTrue(chunks.size() > 1);
            for (int i = 0; i < chunks.size(); i++) {
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    TitlePlayerPageS2C.CODEC.encode(buffer, chunks.get(i));
                    assertTrue(buffer.readableBytes() < 65_536, "Every chunk must fit the payload budget");
                    var decoded = TitlePlayerPageS2C.CODEC.decode(buffer);
                    complete = assembler.accept(decoded);
                    if (i < chunks.size() - 1) assertNull(complete, "Never publish an incomplete JSON page");
                } finally {
                    buffer.release();
                }
            }
            assertNotNull(complete);
            assertEquals(page.playersJson(), complete.playersJson(), "Unicode must survive chunk boundaries");
            seen += JsonParser.parseString(complete.playersJson()).getAsJsonObject().size();
        }
        assertEquals(60, seen, "No title-rich player may disappear between pages");
    }

    @Test
    void resetAndOutOfOrderChunksCannotPublishStaleOrPartialPages() {
        var chunks = TitlePageTransfer.chunks(new TitlePlayerPageS2C("x".repeat(40_000)));
        var assembler = new TitlePageTransfer.Assembler();
        assertNull(assembler.accept(chunks.get(1)));
        assertNull(assembler.accept(chunks.get(0)));
        assembler.reset();
        assertNull(assembler.accept(chunks.get(1)));
        assertNull(assembler.accept(chunks.get(2)));
        for (int i = 0; i < chunks.size() - 1; i++) assertNull(assembler.accept(chunks.get(i)));
        assertNotNull(assembler.accept(chunks.getLast()));
    }
}
