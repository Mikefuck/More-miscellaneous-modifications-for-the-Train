package com.habitrain.lottery.backpack;

import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.Test;

import static com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation.ADD;
import static com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation.SET;
import static org.junit.jupiter.api.Assertions.*;

class PlayerCardMutationPolicyTest {
    @Test
    void acceptsOnlyFourEditableFactionCards() {
        assertEquals(FactionCardType.CIVILIAN, PlayerCardMutationPolicy.parseType("civilian"));
        assertEquals(FactionCardType.NEUTRAL, PlayerCardMutationPolicy.parseType("neutral"));
        assertEquals(FactionCardType.NEUTRAL_FOR_KILLER,
                PlayerCardMutationPolicy.parseType("neutral_for_killer"));
        assertEquals(FactionCardType.KILLER, PlayerCardMutationPolicy.parseType("killer"));
        assertNull(PlayerCardMutationPolicy.parseType("none"));
        assertNull(PlayerCardMutationPolicy.parseType("unknown"));
        assertNull(PlayerCardMutationPolicy.parseType(null));
    }

    @Test
    void addMustBeNonZeroAndWithinOneThousand() {
        assertDoesNotThrow(() -> PlayerCardMutationPolicy.validate(ADD, -1000));
        assertDoesNotThrow(() -> PlayerCardMutationPolicy.validate(ADD, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerCardMutationPolicy.validate(ADD, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerCardMutationPolicy.validate(ADD, -1001));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerCardMutationPolicy.validate(ADD, 1001));
    }

    @Test
    void setMustRemainBetweenZeroAndOneHundredThousand() {
        assertDoesNotThrow(() -> PlayerCardMutationPolicy.validate(SET, 0));
        assertDoesNotThrow(() -> PlayerCardMutationPolicy.validate(SET, 100000));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerCardMutationPolicy.validate(SET, -1));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerCardMutationPolicy.validate(SET, 100001));
    }

    @Test
    void targetCountNeverDropsBelowZero() {
        assertEquals(8, PlayerCardMutationPolicy.targetCount(10, ADD, -2));
        assertEquals(0, PlayerCardMutationPolicy.targetCount(1, ADD, -1000));
        assertEquals(25, PlayerCardMutationPolicy.targetCount(3, SET, 25));
    }
}
