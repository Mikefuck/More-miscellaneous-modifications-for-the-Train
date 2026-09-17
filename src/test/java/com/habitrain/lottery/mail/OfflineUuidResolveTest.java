package com.habitrain.lottery.mail;

import com.habitrain.lottery.network.MailComposeC2SPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfflineUuidResolveTest {

    @Test
    void onlineModeCacheMissIsEmpty() {
        assertTrue(MailComposeC2SPayload.resolveOfflineUuid(true, Optional.empty(), "Steve").isEmpty());
    }

    @Test
    void onlineModeCacheHitReturnsHit() {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertEquals(id, MailComposeC2SPayload.resolveOfflineUuid(true, Optional.of(id), "Steve").orElseThrow());
    }

    @Test
    void offlineModeMissUsesOfflinePlayerUuid() {
        UUID expected = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected,
                MailComposeC2SPayload.resolveOfflineUuid(false, Optional.empty(), "Steve").orElseThrow());
    }

    @Test
    void blankNameIsEmptyEvenOffline() {
        assertTrue(MailComposeC2SPayload.resolveOfflineUuid(false, Optional.empty(), " ").isEmpty());
    }
}
