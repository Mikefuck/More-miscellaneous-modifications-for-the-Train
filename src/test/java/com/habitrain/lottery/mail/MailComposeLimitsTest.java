package com.habitrain.lottery.mail;

import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailComposeLimitsTest {
    @Test
    void targetCountAtMaxIsAccepted() {
        assertEquals(256, MailComposeLimits.requireCount(256, MailTargetParse.MAX_TARGETS, "targets"));
    }

    @Test
    void targetCountAboveMaxThrows() {
        assertThrows(DecoderException.class,
                () -> MailComposeLimits.requireCount(257, MailTargetParse.MAX_TARGETS, "targets"));
    }

    @Test
    void targetCountNegativeThrows() {
        assertThrows(DecoderException.class,
                () -> MailComposeLimits.requireCount(-1, MailTargetParse.MAX_TARGETS, "targets"));
    }

    @Test
    void rewardCountAboveMaxThrows() {
        assertThrows(DecoderException.class,
                () -> MailComposeLimits.requireCount(33, MailComposeLimits.MAX_REWARDS, "rewards"));
    }

    @Test
    void rewardCountAtMaxIsAccepted() {
        assertDoesNotThrow(() -> MailComposeLimits.requireCount(32, MailComposeLimits.MAX_REWARDS, "rewards"));
    }
}
