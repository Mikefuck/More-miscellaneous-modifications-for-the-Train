package com.habitrain.lottery.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailComposeOfflineUuidTest {
    @Test
    void onlineModeCacheMissDoesNotInventOfflinePlayerUuid() {
        Optional<UUID> none = MailComposeC2SPayload.resolveOfflineUuid(true, Optional.empty(), "Steve");
        assertTrue(none.isEmpty());
    }

    @Test
    void offlineModeFallsBackToOfflinePlayerUuid() {
        UUID expected = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8));
        Optional<UUID> got = MailComposeC2SPayload.resolveOfflineUuid(false, Optional.empty(), "Steve");
        assertEquals(Optional.of(expected), got);
    }

    @Test
    void cacheHitWinsOverFallback() {
        UUID mojang = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Optional<UUID> got = MailComposeC2SPayload.resolveOfflineUuid(true, Optional.of(mojang), "Steve");
        assertEquals(Optional.of(mojang), got);
    }
}
