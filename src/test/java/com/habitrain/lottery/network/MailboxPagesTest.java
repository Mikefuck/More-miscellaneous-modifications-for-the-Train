package com.habitrain.lottery.network;

import com.google.gson.Gson;
import com.habitrain.lottery.mail.LocalMailboxStore.MailJson;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MailboxPagesTest {
    @Test void largeUnicodeInboxHasBoundedPagesWithoutMissingOrRepeatedMail() {
        List<MailJson> mails = new ArrayList<>();
        for (int i = 0; i < 126; i++) {
            MailJson mail = new MailJson();
            mail.id = "mail-" + i;
            mail.content = "邮".repeat(8000);
            mail.claimed = i % 2 == 0;
            mails.add(mail);
        }
        var ids = new HashSet<String>();
        int offset = 0;
        do {
            var page = MailboxPages.page(mails, offset);
            assertTrue(page.json().length() <= MailboxPages.MAX_CHARS);
            assertTrue(page.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 1_000_000);
            assertEquals(126, page.total());
            assertEquals(63, page.unclaimed());
            var rows = new Gson().fromJson(page.json(), MailJson[].class);
            assertTrue(rows.length > 0 && rows.length <= MailboxPages.MAX_ROWS);
            for (MailJson row : rows) assertTrue(ids.add(row.id), "duplicate " + row.id);
            assertTrue(page.next() < 0 || page.next() > offset);
            offset = page.next();
        } while (offset >= 0);
        assertEquals(126, ids.size());
    }

    @Test void emptyAndExtremeOffsetsAlwaysReturnUsablePages() {
        assertEquals("[]", MailboxPages.page(List.of(), Integer.MAX_VALUE).json());
        MailJson mail = new MailJson(); mail.id = "single";
        assertEquals(0, MailboxPages.page(List.of(mail), Integer.MAX_VALUE).offset());
        assertEquals(0, MailboxPages.page(List.of(mail), -5).offset());
        assertDoesNotThrow(() -> MailboxPages.forget(null));
    }

    @Test void oversizedExternalLetterKeepsItsClaimIdWithoutOversizedPacket() {
        MailJson mail = new MailJson(); mail.id = "long";
        mail.content = "a".repeat(1_000_001);
        var page = MailboxPages.page(List.of(mail), 0);
        assertTrue(page.json().length() < MailboxPages.MAX_CHARS);
        assertEquals("long", new Gson().fromJson(page.json(), MailJson[].class)[0].id);
        assertEquals(-1, page.next());
        assertEquals(1_000_001, mail.content.length(), "Stored content is not truncated");
    }

    @Test void disconnectResetClearsPrivateCachesAndAdvancesVersions() {
        LotteryNetwork.ClientLotteryState.op = true;
        LotteryNetwork.ClientLotteryState.mailboxJson = "old server mail";
        LotteryNetwork.ClientLotteryState.mailboxNext = 50;
        LotteryNetwork.ClientLotteryState.greenApples = 160;
        LotteryNetwork.ClientLoginState.hasData = true;
        int version = LotteryNetwork.ClientLotteryState.mailboxVersion;
        LotteryNetwork.resetClientState();
        assertFalse(LotteryNetwork.ClientLotteryState.op);
        assertEquals("", LotteryNetwork.ClientLotteryState.mailboxJson);
        assertEquals(-1, LotteryNetwork.ClientLotteryState.mailboxNext);
        assertEquals(0, LotteryNetwork.ClientLotteryState.greenApples);
        assertFalse(LotteryNetwork.ClientLoginState.hasData);
        assertTrue(LotteryNetwork.ClientLotteryState.mailboxVersion > version);
    }
}
