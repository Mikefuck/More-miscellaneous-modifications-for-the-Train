package com.habitrain.lottery.mail;

import com.habitrain.lottery.network.MailComposeC2SPayload;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfflineMailDeliveryTest {
    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        WorldLotteryPaths.clear();
    }

    @Test
    void offlineModeRecipientGetsPersistentMailWithoutLoggingIn() {
        UUID id = MailComposeC2SPayload.resolveOfflineUuid(false, Optional.empty(), "Mike")
                .orElseThrow();
        assertPersistentDelivery(id);
    }

    @Test
    void cachedOnlineModeRecipientGetsPersistentMailWhileOffline() {
        UUID profileId = UUID.randomUUID();
        UUID id = MailComposeC2SPayload.resolveOfflineUuid(true, Optional.of(profileId), "Mike")
                .orElseThrow();
        assertEquals(profileId, id);
        assertPersistentDelivery(id);
    }

    private void assertPersistentDelivery(UUID id) {
        WorldLotteryPaths.initForTests(temp);
        List<MailReward> rewards = List.of(MailReward.greenApples(20), MailReward.selfSelectCard(1));
        MailDraft draft = new MailDraft("system", "Offline delivery", "Please claim after login", 0L, rewards);

        assertFalse(Files.exists(WorldLotteryPaths.playerFile(id)));
        assertTrue(MailService.sendOffline(id, "Mike", draft));
        assertTrue(Files.isRegularFile(MetaFeaturePaths.mailPlayer(id)));
        assertFalse(Files.exists(WorldLotteryPaths.playerFile(id)));

        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTests(temp);
        var loaded = LocalMailboxStore.loadWithStatus(id);
        assertTrue(loaded.ok());
        assertEquals(1, loaded.mails().size());
        var mail = loaded.mails().getFirst();
        assertEquals(draft.title(), mail.title);
        assertEquals(draft.content(), mail.content);
        assertFalse(mail.claimed);
        assertFalse(mail.read);
        assertFalse(LocalMailboxStore.isExpired(mail));
        assertEquals(rewards, MailCommandsCodec.decode(mail.commands));
    }
}
