package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyRewardRecoveryTest {
    @Test void waitsForFollowingTickAndForPlayerStateReset() {
        LobbyRewardRecovery queue = new LobbyRewardRecovery();
        UUID player = UUID.randomUUID();
        List<UUID> delivered = new ArrayList<>();
        queue.schedule(10, List.of(player));
        queue.recover(10, uuid -> true, delivered::add);
        assertTrue(delivered.isEmpty());
        queue.recover(11, uuid -> false, delivered::add);
        assertTrue(delivered.isEmpty());
        queue.recover(12, uuid -> true, delivered::add);
        queue.recover(13, uuid -> true, delivered::add);
        assertEquals(List.of(player), delivered);
    }

    @Test void stoppingClearsQueuedPlayersBeforeNextWorldStarts() {
        LobbyRewardRecovery queue = new LobbyRewardRecovery();
        queue.schedule(10, List.of(UUID.randomUUID()));
        queue.clear();
        List<UUID> delivered = new ArrayList<>();
        queue.recover(12, uuid -> true, delivered::add);
        assertTrue(delivered.isEmpty());
    }
}
