package com.habitrain.lottery.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerCardModifyPayloadTest {
    @Test
    void payloadKeepsTargetTypeOperationAndValue() {
        String target = UUID.randomUUID().toString();

        var payload = new LotteryNetwork.PlayerCardModifyC2S(
                target, "killer", "SET", 8);

        assertEquals(target, payload.targetUuid());
        assertEquals("killer", payload.questKey());
        assertEquals("SET", payload.operation());
        assertEquals(8, payload.value());
    }
}
