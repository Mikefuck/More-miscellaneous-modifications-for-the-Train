package com.habitrain.lottery.grant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GrantRecipientsTest {

    @Test
    void prefersHistoryAndAssignedOverRoundEndSpectators() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID spectator = UUID.randomUUID();
        Set<UUID> match = GrantRecipients.matchParticipants(
                List.of(spectator, a, b), List.of(a), List.of(b));
        assertEquals(Set.of(a, b), match);
        assertFalse(match.contains(spectator));
    }

    @Test
    void historyOrAssignedIgnoresRoundEndEvenWhenRoundEndIsNonEmpty() {
        UUID hist = UUID.randomUUID();
        UUID assigned = UUID.randomUUID();
        UUID spectator = UUID.randomUUID();
        assertEquals(Set.of(hist, assigned),
                GrantRecipients.matchParticipants(List.of(spectator), List.of(hist), List.of(assigned)));
        assertEquals(Set.of(hist),
                GrantRecipients.matchParticipants(List.of(spectator), List.of(hist), List.of()));
        assertEquals(Set.of(assigned),
                GrantRecipients.matchParticipants(List.of(spectator), null, List.of(assigned)));
    }

    @Test
    void emptyHistoryAndAssignedFallsBackToRoundEndAndDoesNotInventPlayers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(Set.of(a, b), GrantRecipients.matchParticipants(List.of(a, b), List.of(), List.of()));
        assertEquals(Set.of(a, b), GrantRecipients.matchParticipants(List.of(a, b), null, null));
        UUID hist = UUID.randomUUID();
        UUID assigned = UUID.randomUUID();
        assertEquals(Set.of(hist, assigned),
                GrantRecipients.matchParticipants(List.of(), List.of(hist), List.of(assigned)));
        assertEquals(Set.of(assigned), GrantRecipients.matchParticipants(null, List.of(), List.of(assigned)));
        assertTrue(GrantRecipients.matchParticipants(null, null, null).isEmpty());
    }

    @Test
    void unionKeepsBothSidesWithoutNulls() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(Set.of(a, b), GrantRecipients.union(List.of(a), java.util.Arrays.asList(b, null)));
    }
}
