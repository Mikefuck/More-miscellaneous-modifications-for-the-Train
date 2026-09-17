package com.habitrain.lottery.mail;

import com.habitrain.lottery.mail.LocalMailboxStore.MailJson;
import com.habitrain.lottery.mail.LocalMailboxStore.MailLoad;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalMailboxStoreTest {
    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        WorldLotteryPaths.clear();
    }

    @Test
    void missingFileIsEmptyWritableMailbox() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();
        MailLoad load = LocalMailboxStore.loadWithStatus(id);
        assertTrue(load.missing());
        assertFalse(load.corrupt());
        assertNotNull(load.mails());
        assertTrue(load.mails().isEmpty());

        MailJson mail = new MailJson();
        mail.id = "m1";
        mail.title = "hi";
        List<MailJson> list = new ArrayList<>();
        list.add(mail);
        assertTrue(LocalMailboxStore.save(id, list));
        MailLoad again = LocalMailboxStore.loadWithStatus(id);
        assertTrue(again.ok());
        assertEquals(1, again.mails().size());
        assertEquals("m1", again.mails().get(0).id);
    }

    @Test
    void corruptExistingFileIsSentinelNotEmptySuccess() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();
        Path file = temp.resolve("mail").resolve("players").resolve(id + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);

        MailLoad load = LocalMailboxStore.loadWithStatus(id);
        assertTrue(load.corrupt());
        assertNull(load.mails());
    }

    @Test
    void legacyLoadDescriptorReturnsMailList() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();

        assertEquals(List.class,
                LocalMailboxStore.class.getMethod("load", UUID.class).getReturnType());
        assertNotNull(LocalMailboxStore.load(id));
        assertTrue(LocalMailboxStore.load(id).isEmpty());
    }
}
